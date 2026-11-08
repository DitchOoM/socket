@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.IPPROTO_IPV6
import platform.posix.IPV6_V6ONLY
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An NW listener is dual-stack `::`, which on Darwin does not own the IPv4 half of its port. With
 * another socket already listening on `0.0.0.0:P`, or on `127.0.0.1:P` the way a JVM binds loopback
 * (an `AF_INET6` socket on `::ffff:127.0.0.1`), a listener on `P` still started, and every connect to
 * `127.0.0.1:P` completed against the other socket. The listener heard nothing, and a TLS client waited
 * for a ServerHello that never came.
 *
 * An ephemeral listen hit this by chance: the IPv6 table it draws from sees neither holder, and a host
 * running Gradle or IDE daemons has loopback listeners across the ephemeral range. On a developer Mac,
 * a JetBrains and a postgres loopback listener were each measured taking the connection meant for a
 * dual-stack listener on the same port. A named port hits it every time, so these tests take the named
 * case; both go through the same port probe.
 */
class AppleListenerIpv4PortTests {
    @Test
    fun aListenerRefusesAPortAnIpv4WildcardListenerHolds() = assertRefusedWhileHeld(::ipv4WildcardListener)

    @Test
    fun aListenerRefusesAPortAJvmStyleLoopbackListenerHolds() = assertRefusedWhileHeld(::v4MappedLoopbackListener)

    private fun assertRefusedWhileHeld(holderFactory: () -> Pair<Int, Int>) =
        runTestNoTimeSkipping {
            val (holder, port) = holderFactory()
            try {
                val server = ServerSocket.allocate()
                val error =
                    try {
                        server.bind(port = port)
                        server.close()
                        fail("a listener on $port started while another socket holds its IPv4 half, so 127.0.0.1:$port never reaches it")
                    } catch (e: SocketException) {
                        e
                    }
                assertTrue(error.message.orEmpty().contains("$port"), "the refusal names the port: ${error.message}")
            } finally {
                close(holder)
            }
        }

    /** A plain `AF_INET` socket listening on `0.0.0.0` at a port the kernel picks: (descriptor, port). */
    private fun ipv4WildcardListener(): Pair<Int, Int> {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket(AF_INET) failed" }
        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_len = sizeOf<sockaddr_in>().convert()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = 0u
            addr.sin_addr.s_addr = 0u // INADDR_ANY
            check(bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind(0.0.0.0:0) failed" }
            check(listen(fd, 1) == 0) { "listen failed" }
            val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            check(getsockname(fd, addr.ptr.reinterpret(), len.ptr) == 0) { "getsockname failed" }
            return fd to hostOrder(addr.sin_port.toInt())
        }
    }

    /** An `AF_INET6` socket listening on `::ffff:127.0.0.1`, as a JVM binds loopback: (descriptor, port). */
    private fun v4MappedLoopbackListener(): Pair<Int, Int> {
        val fd = socket(AF_INET6, SOCK_STREAM, 0)
        check(fd >= 0) { "socket(AF_INET6) failed" }
        memScoped {
            val off = alloc<IntVar>().apply { value = 0 }
            setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, off.ptr, sizeOf<IntVar>().convert())
            val addr = alloc<sockaddr_in6>()
            addr.sin6_len = sizeOf<sockaddr_in6>().convert()
            addr.sin6_family = AF_INET6.convert()
            addr.sin6_port = 0u
            val bytes = addr.sin6_addr.__u6_addr.__u6_addr8
            for (i in 0 until 16) bytes[i] = 0u
            bytes[10] = 0xFFu
            bytes[11] = 0xFFu
            bytes[12] = 127u
            bytes[15] = 1u
            check(bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in6>().convert()) == 0) { "bind(::ffff:127.0.0.1:0) failed" }
            check(listen(fd, 1) == 0) { "listen failed" }
            val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in6>().convert() }
            check(getsockname(fd, addr.ptr.reinterpret(), len.ptr) == 0) { "getsockname failed" }
            return fd to hostOrder(addr.sin6_port.toInt())
        }
    }

    private fun hostOrder(networkOrder: Int) = ((networkOrder and 0xFF) shl 8) or ((networkOrder shr 8) and 0xFF)
}
