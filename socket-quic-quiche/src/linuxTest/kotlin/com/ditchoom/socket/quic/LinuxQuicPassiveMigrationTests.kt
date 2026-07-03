@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import platform.posix.AF_INET
import platform.posix.F_OK
import platform.posix.INADDR_LOOPBACK
import platform.posix.SOCK_DGRAM
import platform.posix.access
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.getsockname
import platform.posix.htonl
import platform.posix.htons
import platform.posix.memcpy
import platform.posix.memset
import platform.posix.ntohs
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.concurrent.AtomicInt

/**
 * Kotlin/Native (linuxX64) passive NAT-rebind migration test — the K/N member of the shared
 * [QuicPassiveMigrationTestSuite], sibling of the active-migration `LinuxQuicMigrationLoopbackTests`.
 *
 * Provides the linuxX64 cert resolution and a [RebindingProxy] built on the repo's io_uring UDP
 * primitives. Native compiles quiche via cinterop, so there is no `UnsatisfiedLinkError` skip path
 * ([wrapTestBody] stays the default pass-through): on Linux the test always runs.
 */
class LinuxQuicPassiveMigrationTests : QuicPassiveMigrationTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override fun createRebindingProxy(serverPort: Int): RebindingProxy = IoUringRebindingProxy(serverPort)

    /**
     * [RebindingProxy] built on the repo's io_uring UDP primitives rather than blocking
     * sockets/threads: [IoUringUdpServerChannel] for the unconnected client-facing socket (its
     * `recvFrom` yields the client source so replies route back), [IoUringUdpChannel] for the
     * connected upstream.
     *
     * Two suspend pump loops run on [Dispatchers.Default]. [upstreamFd] is an [AtomicInt] so the
     * swap in [rebind] is visible to both loops; closing the old fd makes the suspended upstream
     * `receive` return `-ECANCELED` (exactly as the server's `closeFd()` unblocks its recvmsg), so
     * the server-to-client loop simply re-reads the new upstream and continues.
     */
    private class IoUringRebindingProxy(
        private val serverPort: Int,
    ) : RebindingProxy {
        private val bufferFactory = BufferFactory.deterministic()

        // Client-facing bound socket (unconnected) — learns the client's source on the first packet.
        private val clientFd: Int = openBoundLoopbackSocket()
        override val proxyPort: Int = boundPortOf(clientFd)
        private val clientChannel = IoUringUdpServerChannel(clientFd)

        // Upstream (server-facing) connected socket; swapped on rebind. AtomicInt gives the pump
        // loops a consistent view of the current fd across Dispatchers.Default worker threads.
        private val upstreamFd = AtomicInt(newUpstream())

        // Pinned copy of the client's source addr (recvFrom's internal buffer is reused each call).
        // The client-to-server loop fills it and publishes its length; >0 means "ready".
        private val clientAddr = nativeHeap.alloc<sockaddr_storage>()
        private val clientAddrLen = AtomicInt(0)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val c2sJob: Job
        private val s2cJob: Job

        init {
            memset(clientAddr.ptr, 0, sizeOf<sockaddr_storage>().convert())
            c2sJob = scope.launch { clientToServerLoop() }
            s2cJob = scope.launch { serverToClientLoop() }
        }

        /** Client → server: forward each datagram to the current upstream, capturing the client source. */
        private suspend fun clientToServerLoop() {
            val buf = bufferFactory.allocate(MAX_DATAGRAM)
            try {
                while (true) {
                    val r = clientChannel.recvFrom(buf)
                    if (r.bytesReceived <= 0) continue
                    if (clientAddrLen.value == 0 && r.peerAddrLen.toInt() > 0) {
                        memcpy(clientAddr.ptr, r.peerAddr, r.peerAddrLen.convert())
                        clientAddrLen.value = r.peerAddrLen.toInt() // publish after the copy
                    }
                    IoUringUdpChannel(upstreamFd.value).send(buf, r.bytesReceived, dest = null)
                }
            } finally {
                buf.freeNativeMemory()
            }
        }

        /** Server → client: forward each datagram from the current upstream back to the client source. */
        private suspend fun serverToClientLoop() {
            val buf = bufferFactory.allocate(MAX_DATAGRAM)
            try {
                while (true) {
                    // -ETIMEDOUT (idle) or -ECANCELED (fd closed by rebind) → re-read the upstream fd.
                    val n = IoUringUdpChannel(upstreamFd.value).receive(buf)
                    if (n <= 0) continue
                    val len = clientAddrLen.value
                    if (len > 0) {
                        clientChannel.sendTo(buf, n, clientAddr.ptr.reinterpret(), len.convert())
                    }
                }
            } finally {
                buf.freeNativeMemory()
            }
        }

        override fun rebind() {
            val old = upstreamFd.value
            upstreamFd.value = newUpstream()
            close(old) // unblocks the in-flight upstream receive (-ECANCELED); the loop re-reads the new fd
        }

        override suspend fun close() {
            // Stop the loops before closing the client fd / freeing recv buffers — the cancellation
            // path of submitAndWait waits for the kernel to release any in-flight buffer first.
            c2sJob.cancelAndJoin()
            s2cJob.cancelAndJoin()
            clientChannel.close() // closes clientFd + frees its recv structures
            close(upstreamFd.value)
            nativeHeap.free(clientAddr)
            scope.cancel()
        }

        private fun newUpstream(): Int {
            val fd = socket(AF_INET, SOCK_DGRAM, 0)
            check(fd >= 0) { "proxy upstream socket() failed" }
            memScoped {
                val addr = alloc<sockaddr_in>()
                addr.sin_family = AF_INET.convert()
                addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK)
                addr.sin_port = htons(serverPort.toUShort())
                val rc = connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
                check(rc == 0) { "proxy upstream connect() failed (rc=$rc)" }
            }
            return fd
        }

        companion object {
            private const val MAX_DATAGRAM = 2048

            private fun openBoundLoopbackSocket(): Int {
                val fd = socket(AF_INET, SOCK_DGRAM, 0)
                check(fd >= 0) { "proxy client-facing socket() failed" }
                memScoped {
                    val addr = alloc<sockaddr_in>()
                    addr.sin_family = AF_INET.convert()
                    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK)
                    addr.sin_port = htons(0u)
                    val rc = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
                    check(rc == 0) { "proxy client-facing bind() failed (rc=$rc)" }
                }
                return fd
            }

            private fun boundPortOf(fd: Int): Int =
                memScoped {
                    val addr = alloc<sockaddr_in>()
                    val len = alloc<socklen_tVar>()
                    len.value = sizeOf<sockaddr_in>().convert()
                    getsockname(fd, addr.ptr.reinterpret<sockaddr>(), len.ptr)
                    ntohs(addr.sin_port).toInt()
                }
        }
    }
}
