@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.runBlocking
import platform.posix.AF_INET
import platform.posix.IPPROTO_UDP
import platform.posix.SOCK_DGRAM
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * On Darwin a dual-stack `[::]:P` bind succeeds while an `AF_INET` wildcard socket holds `0.0.0.0:P`, which then
 * receives every IPv4 datagram (#450): a serve on `::` must answer that the port is taken instead of serving deaf.
 */
class AppleDualStackWildcardBindTests {
    @Test
    fun aServeOnThePortOfAnIpv4WildcardHolderIsRefusedNotDeaf(): Unit =
        runBlocking {
            val holder = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            assertTrue(holder >= 0, "an AF_INET socket")
            try {
                val port = boundAnywhere(holder)
                val served = serveQuicOnCore(port, "::", platformTlsConfig(), OPTIONS) { "served" }
                val failed = assertIs<QuicServe.Failed>(served, "a serve on [::]:$port while 0.0.0.0:$port is held")
                assertIs<QuicSocketFault.OpenRefused>(failed.fault)
            } finally {
                close(holder)
            }
        }

    /** Binds [fd] to `0.0.0.0:0` and answers the port it was given. */
    private fun boundAnywhere(fd: Int): Int =
        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_len = sizeOf<sockaddr_in>().convert()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = 0u
            addr.sin_addr.s_addr = 0u
            assertEquals(0, bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()), "bind 0.0.0.0:0")
            val len = alloc<UIntVar>()
            len.value = sizeOf<sockaddr_in>().convert()
            assertEquals(0, getsockname(fd, addr.ptr.reinterpret(), len.ptr), "getsockname")
            val network = addr.sin_port.toInt()
            ((network and 0xFF) shl 8) or ((network shr 8) and 0xFF)
        }

    private companion object {
        val OPTIONS = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)
    }
}
