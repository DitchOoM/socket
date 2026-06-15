@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import platform.posix.close
import platform.posix.memcpy
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.socklen_tVar
import kotlin.concurrent.AtomicInt

/**
 * Apple (Network.framework) run of the shared [QuicImpairmentTestSuite] — common-API parity (issue
 * #112). The impairing proxy is the K/N analog of the JVM member's blocking-`DatagramChannel`
 * design: blocking POSIX `recvfrom`/`sendto` on a recv timeout, two pump coroutines applying the
 * policy per datagram. (Linux uses io_uring only because that's its native UDP primitive; there is
 * no Apple UDP primitive to reuse.) The [DirectionPump] policy logic mirrors the Linux sibling.
 */
class AppleQuicImpairmentTests : QuicImpairmentTestSuite() {
    override fun testTlsConfig() = appleQuicTestTlsConfig()

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        if (shouldSkipQuicHarnessOnSimulator()) return
        block()
    }

    override fun createImpairingProxy(
        serverPort: Int,
        policy: ImpairmentPolicy,
    ): ImpairingProxy = PosixImpairingProxy(serverPort, policy)

    private class PosixImpairingProxy(
        serverPort: Int,
        private val policy: ImpairmentPolicy,
    ) : ImpairingProxy {
        private val bufferFactory = BufferFactory.deterministic()

        private val clientFd: Int = proxyOpenBoundLoopbackSocket().also { proxySetRecvTimeout(it, RECV_TIMEOUT_MS) }
        override val proxyPort: Int = proxyBoundPort(clientFd)
        private val upstreamFd: Int = proxyNewUpstream(serverPort).also { proxySetRecvTimeout(it, RECV_TIMEOUT_MS) }

        // Pinned copy of the client's source addr — the c2s loop fills it and publishes its length.
        private val clientAddr = nativeHeap.alloc<sockaddr_storage>()
        private val clientAddrLen = AtomicInt(0)

        private val armedFlag = AtomicInt(0)
        private val running = AtomicInt(1)
        private val dropped = AtomicInt(0)
        private val duplicated = AtomicInt(0)
        private val delayed = AtomicInt(0)
        private val reordered = AtomicInt(0)

        override val droppedCount get() = dropped.value
        override val duplicatedCount get() = duplicated.value
        override val delayedCount get() = delayed.value
        override val reorderedCount get() = reordered.value

        override fun arm() {
            armedFlag.value = 1
        }

        private val supervisor = SupervisorJob()
        private val scope = CoroutineScope(supervisor + Dispatchers.Default)

        // Dedicated threads for the blocking recv loops (mirrors the JVM proxy's daemon threads): a
        // tight blocking-recvfrom loop never suspends, so running it on Dispatchers.Default would pin
        // a shared worker and starve the QUIC client/server coroutines, stalling all data flow.
        private val c2sThread = newSingleThreadContext("apple-impair-c2s")
        private val s2cThread = newSingleThreadContext("apple-impair-s2c")

        /** Per-direction index + held-datagram slot. [send] forwards [n] bytes from a buffer. Mirrors Linux. */
        private inner class DirectionPump(
            private val direction: ImpairDirection,
            private val send: (PlatformBuffer, Int) -> Unit,
        ) {
            private val index = AtomicInt(0)
            private var held: PlatformBuffer? = null
            private var heldLen = 0

            fun handle(
                buf: PlatformBuffer,
                n: Int,
            ) {
                if (armedFlag.value == 0) {
                    send(buf, n)
                    return
                }
                val toRelease = held
                val toReleaseLen = heldLen
                held = null
                when (val action = policy.actionFor(direction, index.getAndIncrement())) {
                    ImpairAction.Forward -> send(buf, n)
                    ImpairAction.Drop -> dropped.incrementAndGet()
                    ImpairAction.ForwardTwice -> {
                        send(buf, n)
                        send(buf, n)
                        duplicated.incrementAndGet()
                    }
                    is ImpairAction.ForwardAfter -> {
                        val copy = copyOf(buf, n)
                        scope.launch {
                            try {
                                delay(action.delayMs)
                                send(copy, n)
                            } finally {
                                copy.freeNativeMemory()
                            }
                        }
                        delayed.incrementAndGet()
                    }
                    ImpairAction.HoldUntilNext -> {
                        held = copyOf(buf, n)
                        heldLen = n
                        reordered.incrementAndGet()
                    }
                }
                // Release the previously-held datagram AFTER the current one — the structural reorder.
                if (toRelease != null) {
                    send(toRelease, toReleaseLen)
                    toRelease.freeNativeMemory()
                }
            }

            fun freeHeld() {
                held?.freeNativeMemory()
                held = null
            }
        }

        private val c2sPump =
            DirectionPump(ImpairDirection.ClientToServer) { b, n ->
                send(upstreamFd, b.nativeBytePtr(), n.convert(), 0)
            }
        private val s2cPump =
            DirectionPump(ImpairDirection.ServerToClient) { b, n ->
                if (clientAddrLen.value > 0) {
                    sendto(
                        clientFd,
                        b.nativeBytePtr(),
                        n.convert(),
                        0,
                        clientAddr.ptr.reinterpret<sockaddr>(),
                        clientAddrLen.value.convert(),
                    )
                }
            }

        private val c2sJob = scope.launch(c2sThread) { clientToServerLoop() }
        private val s2cJob = scope.launch(s2cThread) { serverToClientLoop() }

        /** Client → server: capture the client source on the first packet, then apply the policy. */
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
                    if (n <= 0) continue // recv timeout (EAGAIN) or transient — re-check `running`
                    if (clientAddrLen.value == 0 && peerLen.value.toInt() > 0) {
                        memcpy(clientAddr.ptr, peer.ptr, peerLen.value.convert())
                        clientAddrLen.value = peerLen.value.toInt() // publish after the copy
                    }
                    c2sPump.handle(buf, n)
                }
            } finally {
                buf.freeNativeMemory()
                nativeHeap.free(peer)
                nativeHeap.free(peerLen)
            }
        }

        /** Server → client: apply the policy and forward to the captured client source. */
        private suspend fun serverToClientLoop() {
            val buf = bufferFactory.allocate(PROXY_MAX_DATAGRAM)
            try {
                while (running.value == 1) {
                    val n = recv(upstreamFd, buf.nativeBytePtr(), PROXY_MAX_DATAGRAM.convert(), 0).toInt()
                    if (running.value == 0) break
                    if (n <= 0) continue
                    s2cPump.handle(buf, n)
                }
            } finally {
                buf.freeNativeMemory()
            }
        }

        private fun copyOf(
            src: PlatformBuffer,
            n: Int,
        ): PlatformBuffer {
            val dst = bufferFactory.allocate(n)
            memcpy(dst.nativeBytePtr(), src.nativeBytePtr(), n.convert())
            return dst
        }

        override suspend fun close() {
            running.value = 0
            close(clientFd) // unblock recvfrom (also bounded by the recv timeout)
            close(upstreamFd)
            c2sJob.cancelAndJoin()
            s2cJob.cancelAndJoin()
            supervisor.cancelAndJoin() // cancel any in-flight delayed-send children
            c2sThread.close()
            s2cThread.close()
            c2sPump.freeHeld()
            s2cPump.freeHeld()
            nativeHeap.free(clientAddr)
        }

        private companion object {
            private const val RECV_TIMEOUT_MS = 150
        }
    }
}
