@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An NW listener is dual-stack `::`, which on Darwin does not own the IPv4 half of its port: with a
 * plain `AF_INET` socket already listening on `0.0.0.0:P`, a listener on `P` still starts, and every
 * connect to `127.0.0.1:P` completes against the other socket. The listener hears nothing, and a TLS
 * client waits for a ServerHello that never comes.
 *
 * An ephemeral listen hit this by chance — NW drew the port from the IPv6 table, so another process's
 * IPv4 listener could already hold it — and a named port hits it every time. This test takes the named
 * case because it is deterministic; both now go through the same IPv4-first port choice.
 */
class AppleListenerIpv4PortTests {
    @Test
    fun aListenerRefusesAPortWhoseIpv4HalfAnotherSocketHolds() =
        runTestNoTimeSkipping {
            val (holder, port) = ipv4WildcardListener()
            try {
                val server = ServerSocket.allocate()
                val error =
                    try {
                        server.bind(port = port)
                        server.close()
                        fail("a listener on $port started while 0.0.0.0:$port is another socket's, so 127.0.0.1:$port never reaches it")
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
            val networkOrder = addr.sin_port.toInt()
            return fd to (((networkOrder and 0xFF) shl 8) or ((networkOrder shr 8) and 0xFF))
        }
    }
}
