@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end **passive** connection migration (RFC 9000 §9.3 NAT rebinding) on Kotlin/Native — the
 * K/N counterpart of the JVM `QuicPassiveMigrationTests` (#69) and the linuxX64 sibling of the
 * active-migration `LinuxQuicMigrationLoopbackTests` (#67).
 *
 * The client never calls [QuicScope.migrate]; instead the path's *source* address changes
 * underneath it, as a NAT rebind would. This exercises the K/N server-side routing
 * (`LinuxQuicServer.recvInfoFor` per-source recv_info + `ServerConnectionUdpChannel`'s `sendInfo.to`
 * egress): quiche sees the rebound source as the same connection (unchanged DCID) and replies
 * follow the peer to its new 4-tuple.
 *
 * The rebind is simulated by a userspace UDP proxy (no root / netns / tc): the client talks to the
 * proxy, the proxy forwards to the in-repo server, and mid-stream the proxy swaps its *upstream*
 * (server-facing) socket for one with a fresh source port. From the server's view that's a single
 * connection whose source 4-tuple suddenly changed. We assert the stream still round-trips after.
 *
 * The K/N proxy is built on the same io_uring UDP primitives the server uses
 * ([IoUringUdpServerChannel] for the unconnected client-facing socket, [IoUringUdpChannel] for the
 * connected upstream) — fully reactive, no blocking threads. See [RebindingUdpProxy].
 *
 * Native targets compile quiche via cinterop, so there is no `UnsatisfiedLinkError` skip path: on
 * Linux this test always runs (like its migration sibling). Any failure is a real failure, which is
 * the same "must run, never silently skip" discipline `QUIC_MIGRATION_REQUIRE_RUN` enforces on JVM.
 */
class LinuxQuicPassiveMigrationTests {
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    private suspend fun QuicByteStream.echoOnce(payload: String): String {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 5.seconds)
        val resp = read(5.seconds)
        return if (resp is ReadResult.Data) resp.buffer.readString(resp.buffer.remaining(), Charset.UTF8) else "no_data"
    }

    @Test
    fun streamSurvivesPassiveSourceRebind() =
        runQuicTest {
            withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                // Echo loop: mirror every message back until the stream ends.
                val serverJob =
                    launch {
                        connections {
                            val stream = acceptStream()
                            while (true) {
                                val data = stream.read(8.seconds)
                                if (data is ReadResult.Data) {
                                    stream.write(data.buffer, 5.seconds)
                                } else {
                                    break
                                }
                            }
                            stream.close()
                        }
                    }

                val proxy = RebindingUdpProxy(serverPort = port)
                val beforeEcho = CompletableDeferred<String>()
                val afterEcho = CompletableDeferred<String>()

                val clientJob =
                    launch {
                        // Client connects to the proxy, unaware of the rebind that happens on the
                        // proxy's upstream (server-facing) socket.
                        withQuicConnection("127.0.0.1", proxy.proxyPort, testQuicOptions, timeout = 10.seconds) {
                            val stream = openStream()
                            beforeEcho.complete(stream.echoOnce("before"))

                            // Passive rebind: the proxy's source toward the server changes, with NO
                            // client-side migrate(). The server must keep the stream alive via
                            // per-source recv_info + sendInfo.to routing.
                            proxy.rebind()

                            afterEcho.complete(stream.echoOnce("after"))
                            stream.close()
                        }
                    }

                try {
                    assertEquals("before", beforeEcho.await())
                    assertEquals(
                        "after",
                        afterEcho.await(),
                        "stream did not round-trip after passive source rebind",
                    )
                } finally {
                    clientJob.cancel()
                    serverJob.cancel()
                    proxy.close()
                }
            }
        }

    /**
     * Userspace UDP forwarder that simulates a NAT rebind, on Kotlin/Native. Client ↔ [proxyPort] ↔
     * server. [rebind] swaps the upstream (server-facing) socket for one with a new source port, so
     * the server sees the same connection arrive from a new 4-tuple.
     *
     * Built on the repo's io_uring UDP primitives rather than blocking sockets/threads:
     *  - the client-facing socket is bound + unconnected, driven by [IoUringUdpServerChannel] whose
     *    `recvFrom` yields the client's source address (so replies can be sent back to it);
     *  - the upstream socket is `connect()`ed to the server and driven by [IoUringUdpChannel].
     *
     * Two suspend pump loops run on [Dispatchers.Default]. [upstreamFd] is an [AtomicInt] so the
     * swap in [rebind] is visible to both loops; closing the old fd makes the suspended upstream
     * `receive` return `-ECANCELED` (exactly as the server's `closeFd()` unblocks its recvmsg), so
     * the server-to-client loop simply re-reads the new upstream and continues.
     */
    private class RebindingUdpProxy(
        private val serverPort: Int,
    ) {
        private val bufferFactory = BufferFactory.deterministic()

        // Client-facing bound socket (unconnected) — learns the client's source on the first packet.
        private val clientFd: Int = openBoundLoopbackSocket()
        val proxyPort: Int = boundPortOf(clientFd)
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

        /** Swap the upstream socket for a fresh source port — the NAT rebind. */
        fun rebind() {
            val old = upstreamFd.value
            upstreamFd.value = newUpstream()
            close(old) // unblocks the in-flight upstream receive (-ECANCELED); the loop re-reads the new fd
        }

        suspend fun close() {
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
