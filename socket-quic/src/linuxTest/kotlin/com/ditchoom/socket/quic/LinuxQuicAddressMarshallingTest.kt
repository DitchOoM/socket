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
import com.ditchoom.socket.quic.quiche.quiche_connect
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.htonl
import platform.posix.htons
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for quiche/src/ffi.rs:2059 "unsupported address type" panic.
 *
 * Quiche's Rust FFI asserts `sa_family in {AF_INET, AF_INET6}` on every sockaddr
 * it is handed; otherwise `std_addr_from_c` panics via `unimplemented!()` and
 * SIGABRTs through the FFI boundary. When `LinuxQuicSmokeTest.quiche_connect_creates_connection`
 * started crashing, the blast radius covered three independent pointer sources:
 * `socket_getsockname` output, `getaddrinfo` output, and K/N struct marshalling.
 * These tests isolate each so a future regression fingers the specific helper
 * that broke instead of pointing at a Rust panic deep inside quiche.
 */
class LinuxQuicAddressMarshallingTest {
    // Isolation A: socket_getsockname on a connected UDP socket must set sin_family.
    // Pre-poisons the struct so a "forgot to write" bug can't masquerade as success.
    @Test
    fun socket_getsockname_populates_sin_family() {
        memScoped {
            val fd = socket(AF_INET, SOCK_DGRAM, 0)
            assertTrue(fd >= 0, "socket() failed: $fd")
            try {
                val peer = alloc<sockaddr_in>()
                peer.sin_family = AF_INET.convert()
                peer.sin_port = htons(443u)
                peer.sin_addr.s_addr = htonl(0x01010101u) // 1.1.1.1
                val cr = connect(fd, peer.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
                assertTrue(cr == 0, "connect() returned $cr")

                val localAddr = alloc<sockaddr_in>()
                // Poison sin_family with a value that is NOT AF_INET(2) or AF_INET6(10).
                localAddr.sin_family = 0xA5u.convert()
                val localLen = alloc<kotlinx.cinterop.UIntVar>()
                localLen.value = sizeOf<sockaddr_in>().convert()

                val rc =
                    com.ditchoom.socket.linux.socket_getsockname(
                        fd,
                        localAddr.ptr.reinterpret(),
                        localLen.ptr,
                    )
                assertEquals(0, rc, "socket_getsockname returned $rc")
                assertEquals(
                    AF_INET,
                    localAddr.sin_family.toInt(),
                    "socket_getsockname did not overwrite sin_family (got ${localAddr.sin_family.toInt()}, expected AF_INET=$AF_INET)",
                )
                assertEquals(
                    sizeOf<sockaddr_in>().toInt(),
                    localLen.value.toInt(),
                    "socket_getsockname returned wrong addr_len",
                )
            } finally {
                close(fd)
            }
        }
    }

    // Isolation B: getaddrinfo with AF_INET hints must return ai_addr with sa_family = AF_INET.
    // Uses 127.0.0.1 (no DNS) so the test is deterministic and offline-safe.
    @Test
    fun getaddrinfo_returns_inet_family_for_ipv4_hint() {
        memScoped {
            val hints = alloc<addrinfo>()
            hints.ai_family = AF_INET
            hints.ai_socktype = SOCK_DGRAM
            val result = alloc<kotlinx.cinterop.CPointerVar<addrinfo>>()
            val rc = getaddrinfo("127.0.0.1", "443", hints.ptr, result.ptr)
            assertEquals(0, rc, "getaddrinfo returned $rc")
            try {
                val first = result.value!!.pointed
                val family =
                    first.ai_addr!!
                        .pointed.sa_family
                        .toInt()
                assertEquals(
                    AF_INET,
                    family,
                    "getaddrinfo returned sa_family=$family for IPv4 hint (expected AF_INET=$AF_INET)",
                )
                assertEquals(
                    sizeOf<sockaddr_in>().toInt(),
                    first.ai_addrlen.toInt(),
                    "getaddrinfo returned wrong ai_addrlen",
                )
            } finally {
                freeaddrinfo(result.value)
            }
        }
    }

    // Isolation C: quiche_connect with manually-built sockaddr_in values (bypassing
    // getsockname and getaddrinfo) must succeed. If A and B both pass but this
    // fails, the bug is in K/N struct marshalling or quiche itself; if this
    // passes, A and B are the only places bad sockaddrs can enter the pipeline.
    @Test
    fun quiche_connect_with_manual_sockaddr_succeeds() {
        val factory = BufferFactory.deterministic()
        val config = quiche_config_new(QUICHE_PROTOCOL_VERSION.convert())!!
        try {
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
                val localAddr = alloc<sockaddr_in>()
                localAddr.sin_family = AF_INET.convert()
                localAddr.sin_port = htons(0u) // OS-assigned
                localAddr.sin_addr.s_addr = 0u // INADDR_ANY

                val peerAddr = alloc<sockaddr_in>()
                peerAddr.sin_family = AF_INET.convert()
                peerAddr.sin_port = htons(443u)
                peerAddr.sin_addr.s_addr = htonl(0x01010101u) // 1.1.1.1

                val scidBytes = ByteArray(QUIC_MAX_CONN_ID_LEN) { it.toByte() }

                val conn =
                    scidBytes.usePinned { pinned ->
                        quiche_connect(
                            "example.com",
                            pinned.addressOf(0).reinterpret(),
                            QUIC_MAX_CONN_ID_LEN.convert(),
                            localAddr.ptr.reinterpret(),
                            sizeOf<sockaddr_in>().convert(),
                            peerAddr.ptr.reinterpret(),
                            sizeOf<sockaddr_in>().convert(),
                            config,
                        )
                    }
                assertNotNull(conn, "quiche_connect returned null with manually-built sockaddrs")
                quiche_conn_free(conn)
            }
        } finally {
            quiche_config_free(config)
        }
    }
}
