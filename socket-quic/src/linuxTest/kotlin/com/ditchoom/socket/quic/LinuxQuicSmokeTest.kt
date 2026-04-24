@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.quiche.QUICHE_PROTOCOL_VERSION
import com.ditchoom.socket.quic.quiche.quiche_config_free
import com.ditchoom.socket.quic.quiche.quiche_config_new
import com.ditchoom.socket.quic.quiche.quiche_config_set_application_protos
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_max_data
import com.ditchoom.socket.quic.quiche.quiche_config_set_initial_max_streams_bidi
import com.ditchoom.socket.quic.quiche.quiche_config_set_max_idle_timeout
import com.ditchoom.socket.quic.quiche.quiche_config_set_max_recv_udp_payload_size
import com.ditchoom.socket.quic.quiche.quiche_config_set_max_send_udp_payload_size
import com.ditchoom.socket.quic.quiche.quiche_config_verify_peer
import com.ditchoom.socket.quic.quiche.quiche_conn_free
import com.ditchoom.socket.quic.quiche.quiche_conn_is_established
import com.ditchoom.socket.quic.quiche.quiche_connect
import com.ditchoom.socket.quic.quiche.quiche_version
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Minimal smoke tests for quiche on Linux K/Native.
 * Tests each layer independently to isolate crashes.
 */
class LinuxQuicSmokeTest {
    @Test
    fun quiche_version_returns_string() {
        val version = quiche_version()!!.toKString()
        assertTrue(version.isNotEmpty(), "quiche_version returned empty string")
        println("quiche version: $version")
    }

    @Test
    fun quiche_config_newAndFree() {
        val config = quiche_config_new(QUICHE_PROTOCOL_VERSION.convert())
        assertNotNull(config, "quiche_config_new returned null")
        quiche_config_free(config)
    }

    @Test
    fun quiche_config_withAlpn() {
        val factory = BufferFactory.deterministic()
        val config = quiche_config_new(QUICHE_PROTOCOL_VERSION.convert())!!

        val alpnBuf = encodeAlpnList(listOf("h3"), factory)
        val alpnPtr = alpnBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<UByteVar>()!!
        val result = quiche_config_set_application_protos(config, alpnPtr, alpnBuf.remaining().convert())
        assertTrue(result == 0, "quiche_config_set_application_protos returned $result")
        alpnBuf.freeNativeMemory()

        quiche_config_set_max_idle_timeout(config, 30000u)
        quiche_config_set_max_recv_udp_payload_size(config, 1350u)
        quiche_config_set_max_send_udp_payload_size(config, 1350u)
        quiche_config_set_initial_max_data(config, 10_485_760u)
        quiche_config_set_initial_max_streams_bidi(config, 100u)
        quiche_config_verify_peer(config, false)

        quiche_config_free(config)
    }

    @Test
    fun udp_socket_and_connect() {
        memScoped {
            val fd = socket(AF_INET, SOCK_DGRAM, 0)
            assertTrue(fd >= 0, "socket() failed: $fd")

            val hints = alloc<addrinfo>()
            hints.ai_family = AF_INET
            hints.ai_socktype = SOCK_DGRAM
            val resultPtr = alloc<kotlinx.cinterop.CPointerVar<addrinfo>>()
            val resolveResult = getaddrinfo("cloudflare-quic.com", "443", hints.ptr, resultPtr.ptr)
            assertTrue(resolveResult == 0, "getaddrinfo failed: $resolveResult")

            val addrInfo = resultPtr.value!!.pointed
            val connectResult = connect(fd, addrInfo.ai_addr, addrInfo.ai_addrlen)
            assertTrue(connectResult == 0, "connect() failed: $connectResult")

            freeaddrinfo(resultPtr.value)
            close(fd)
        }
    }

    @Test
    fun quiche_connect_creates_connection() {
        val factory = BufferFactory.deterministic()
        val config = quiche_config_new(QUICHE_PROTOCOL_VERSION.convert())!!
        val alpnBuf = encodeAlpnList(listOf("h3"), factory)
        val alpnPtr = alpnBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<UByteVar>()!!
        quiche_config_set_application_protos(config, alpnPtr, alpnBuf.remaining().convert())
        alpnBuf.freeNativeMemory()
        quiche_config_set_max_idle_timeout(config, 30000u)
        quiche_config_set_max_recv_udp_payload_size(config, 1350u)
        quiche_config_set_max_send_udp_payload_size(config, 1350u)
        quiche_config_set_initial_max_data(config, 10_485_760u)
        quiche_config_set_initial_max_streams_bidi(config, 100u)
        quiche_config_verify_peer(config, false)

        memScoped {
            val fd = socket(AF_INET, SOCK_DGRAM, 0)
            assertTrue(fd >= 0, "socket() failed: $fd")
            val hints = alloc<addrinfo>()
            hints.ai_family = AF_INET
            hints.ai_socktype = SOCK_DGRAM
            val resultPtr = alloc<kotlinx.cinterop.CPointerVar<addrinfo>>()
            // Resolve a well-known target. Every return value below MUST be checked: an uncaught
            // failure leaves ai_addr / localAddr with garbage sa_family bytes, and quiche_connect
            // then panics inside Rust's `std_addr_from_c` with SIGABRT — indistinguishable from
            // a legitimate crash. See LinuxQuicAddressMarshallingTest for per-helper isolation.
            val rr = getaddrinfo("cloudflare-quic.com", "443", hints.ptr, resultPtr.ptr)
            assertTrue(rr == 0, "getaddrinfo failed: $rr")
            val addrInfo = resultPtr.value!!.pointed
            val peerFamily = addrInfo.ai_addr!!.pointed.sa_family.toInt()
            assertTrue(
                peerFamily == AF_INET,
                "getaddrinfo returned peer sa_family=$peerFamily (expected AF_INET=$AF_INET)",
            )
            val cr = connect(fd, addrInfo.ai_addr, addrInfo.ai_addrlen)
            assertTrue(cr == 0, "connect() returned $cr")

            val localAddr = alloc<sockaddr_in>()
            // Poison sin_family so we can't mistake a silent getsockname failure for success —
            // 0xA5 is intentionally neither AF_INET nor AF_INET6.
            localAddr.sin_family = 0xA5u.convert()
            val localAddrLen = alloc<kotlinx.cinterop.UIntVar>()
            localAddrLen.value = kotlinx.cinterop.sizeOf<sockaddr_in>().convert()
            val gs =
                com.ditchoom.socket.linux
                    .socket_getsockname(fd, localAddr.ptr.reinterpret(), localAddrLen.ptr)
            assertTrue(gs == 0, "socket_getsockname returned $gs")
            val localFamily = localAddr.sin_family.toInt()
            assertTrue(
                localFamily == AF_INET,
                "socket_getsockname left sin_family=$localFamily (expected AF_INET=$AF_INET)",
            )

            val scidBytes =
                ByteArray(QUIC_MAX_CONN_ID_LEN) {
                    kotlin.random.Random.nextInt(256).toByte()
                }

            val conn =
                scidBytes.usePinned { pinned ->
                    quiche_connect(
                        "cloudflare-quic.com",
                        pinned.addressOf(0).reinterpret(),
                        QUIC_MAX_CONN_ID_LEN.convert(),
                        localAddr.ptr.reinterpret(),
                        kotlinx.cinterop.sizeOf<sockaddr_in>().convert(),
                        addrInfo.ai_addr,
                        addrInfo.ai_addrlen,
                        config,
                    )
                }
            assertNotNull(conn, "quiche_connect returned null")
            quiche_conn_free(conn)
            freeaddrinfo(resultPtr.value)
            close(fd)
        }

        quiche_config_free(config)
    }
}
