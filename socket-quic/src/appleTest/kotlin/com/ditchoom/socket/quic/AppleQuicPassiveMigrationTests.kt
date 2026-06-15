@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import platform.posix.close
import platform.posix.memcpy
import platform.posix.memset
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.socklen_tVar
import kotlin.concurrent.AtomicInt

/**
 * Apple (Network.framework) run of the shared [QuicPassiveMigrationTestSuite] — common-API parity
 * (issue #112). The NAT-rebind proxy is the K/N analog of the JVM member's blocking-socket design:
 * blocking POSIX `recvfrom`/`sendto` on a recv timeout, two pump coroutines. [rebind] swaps the
 * upstream (server-facing) socket for one with a new source port, so the server sees the same
 * connection arrive from a new 4-tuple. The server keeps the stream alive via per-source recv_info.
 */
class AppleQuicPassiveMigrationTests : QuicPassiveMigrationTestSuite() {
    override fun testTlsConfig() = appleQuicTestTlsConfig()

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        if (shouldSkipQuicHarnessOnSimulator()) return
        block()
    }

    /**
     * Network.framework's QUIC server does not migrate egress to a rebound peer source, and NW
     * exposes no path-migration control (active `migrate()` is likewise [MigrationResult.Unsupported]
     * on Apple). Observed deterministically: the post-rebind echo always times out, while the 6
     * [QuicImpairmentTestSuite] tests prove this same POSIX proxy forwards correctly — so it's the
     * source-rebind handling, not the proxy. The rebinding proxy below is kept (and compiled) for
     * when NW gains server-side passive migration. (Issue #112.)
     */
    override fun supportsPassiveSourceRebind(): Boolean = false

    override fun createRebindingProxy(serverPort: Int): RebindingProxy = PosixRebindingProxy(serverPort)

    private class PosixRebindingProxy(
        private val serverPort: Int,
    ) : RebindingProxy {
        private val bufferFactory = BufferFactory.deterministic()

        private val clientFd: Int = proxyOpenBoundLoopbackSocket().also { proxySetRecvTimeout(it, RECV_TIMEOUT_MS) }
        override val proxyPort: Int = proxyBoundPort(clientFd)

        // Upstream (server-facing) connected socket; swapped on rebind. AtomicInt so the pump loops
        // see the swap across dispatcher threads.
        private val upstreamFd = AtomicInt(newUpstream())

        // Pinned copy of the client's source addr (recvfrom's peer buffer is reused each call).
        private val clientAddr = nativeHeap.alloc<sockaddr_storage>()
        private val clientAddrLen = AtomicInt(0)

        private val running = AtomicInt(1)
        private val supervisor = SupervisorJob()
        private val scope = CoroutineScope(supervisor + Dispatchers.Default)

        // Dedicated threads for the blocking recv loops (see the impairment proxy's note): a tight
        // blocking-recvfrom loop never suspends, so on Dispatchers.Default it would pin a shared
        // worker and starve the QUIC coroutines.
        private val c2sThread = newSingleThreadContext("apple-rebind-c2s")
        private val s2cThread = newSingleThreadContext("apple-rebind-s2c")
        private val c2sJob = scope.launch(c2sThread) { clientToServerLoop() }
        private val s2cJob = scope.launch(s2cThread) { serverToClientLoop() }

        init {
            memset(clientAddr.ptr, 0, sizeOf<sockaddr_storage>().convert())
        }

        /** Client → server: forward each datagram to the current upstream, capturing the client source. */
        private suspend fun clientToServerLoop() {
            val buf = bufferFactory.allocate(PROXY_MAX_DATAGRAM)
            val peer = nativeHeap.alloc<sockaddr_storage>()
            val peerLen = nativeHeap.alloc<socklen_tVar>()
            try {
                while (running.value == 1) {
                    peerLen.value = sizeOf<sockaddr_storage>().convert()
                    val n =
                        recvfrom(
                            clientFd,
                            buf.nativeBytePtr(),
                            PROXY_MAX_DATAGRAM.convert(),
                            0,
                            peer.ptr.reinterpret<sockaddr>(),
                            peerLen.ptr,
                        ).toInt()
                    if (running.value == 0) break
                    if (n <= 0) continue
                    if (clientAddrLen.value == 0 && peerLen.value.toInt() > 0) {
                        memcpy(clientAddr.ptr, peer.ptr, peerLen.value.convert())
                        clientAddrLen.value = peerLen.value.toInt() // publish after the copy
                    }
                    send(upstreamFd.value, buf.nativeBytePtr(), n.convert(), 0)
                }
            } finally {
                buf.freeNativeMemory()
                nativeHeap.free(peer)
                nativeHeap.free(peerLen)
            }
        }

        /** Server → client: forward each datagram from the current upstream back to the client source. */
        private suspend fun serverToClientLoop() {
            val buf = bufferFactory.allocate(PROXY_MAX_DATAGRAM)
            try {
                while (running.value == 1) {
                    // recv timeout (EAGAIN) or the fd closed by rebind/teardown → re-read upstreamFd.
                    val n = recv(upstreamFd.value, buf.nativeBytePtr(), PROXY_MAX_DATAGRAM.convert(), 0).toInt()
                    if (running.value == 0) break
                    if (n <= 0) continue
                    val len = clientAddrLen.value
                    if (len > 0) {
                        sendto(
                            clientFd,
                            buf.nativeBytePtr(),
                            n.convert(),
                            0,
                            clientAddr.ptr.reinterpret<sockaddr>(),
                            len.convert(),
                        )
                    }
                }
            } finally {
                buf.freeNativeMemory()
            }
        }

        override fun rebind() {
            val old = upstreamFd.value
            upstreamFd.value = newUpstream()
            close(old) // unblocks the in-flight upstream recv; the loop re-reads the new fd
        }

        override suspend fun close() {
            running.value = 0
            close(clientFd)
            close(upstreamFd.value)
            c2sJob.cancelAndJoin()
            s2cJob.cancelAndJoin()
            supervisor.cancelAndJoin()
            c2sThread.close()
            s2cThread.close()
            nativeHeap.free(clientAddr)
        }

        private fun newUpstream(): Int = proxyNewUpstream(serverPort).also { proxySetRecvTimeout(it, RECV_TIMEOUT_MS) }

        private companion object {
            // Wakes the blocking recv loops periodically to re-check `running` / pick up a
            // rebind's swapped fd — shutdown + rebind responsiveness only. recvfrom returns
            // immediately when a datagram is present, so this is NOT a forwarding-latency knob;
            // scaled so a slow runner (QUIC_TEST_TIME_SCALE>1) keeps the cadence proportional.
            private val RECV_TIMEOUT_MS = (150 * testTimeScale()).toInt()
        }
    }
}
