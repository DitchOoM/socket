@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
import platform.posix.ntohs
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.concurrent.AtomicInt

/**
 * Kotlin/Native (linuxX64) network-impairment test — the K/N member of the shared
 * [QuicImpairmentTestSuite], sibling of [LinuxQuicPassiveMigrationTests].
 *
 * Provides linuxX64 cert resolution and an [ImpairingProxy] built on the repo's io_uring UDP
 * primitives ([IoUringUdpServerChannel] for the unconnected client-facing socket, [IoUringUdpChannel]
 * for the connected upstream). Native compiles quiche via cinterop, so there is no
 * `UnsatisfiedLinkError` skip path ([wrapTestBody] stays the default pass-through): on Linux the test
 * always runs.
 */
class LinuxQuicImpairmentTests : QuicImpairmentTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override fun createImpairingProxy(
        serverPort: Int,
        policy: ImpairmentPolicy,
    ): ImpairingProxy = IoUringImpairingProxy(serverPort, policy)

    /**
     * [ImpairingProxy] over io_uring UDP primitives. Two suspend pump loops on [Dispatchers.Default]
     * apply [policy] per datagram once [arm]ed. Datagrams that outlive the next `recvFrom`
     * ([ImpairAction.ForwardAfter], [ImpairAction.HoldUntilNext]) are copied out of the reused recv
     * buffer (its payload + `peerAddr` are valid only until the next recv); duplicates re-send the live
     * buffer (both sends complete before the next recv). A delayed send runs in a child coroutine so the
     * pump keeps draining; teardown cancels+joins every child before freeing held/recv buffers and fds —
     * the io_uring cancellation path waits for the kernel to release in-flight buffers first.
     */
    private class IoUringImpairingProxy(
        serverPort: Int,
        private val policy: ImpairmentPolicy,
    ) : ImpairingProxy {
        private val bufferFactory = BufferFactory.deterministic()

        private val clientFd: Int = openBoundLoopbackSocket()
        override val proxyPort: Int = boundPortOf(clientFd)
        private val clientChannel = IoUringUdpServerChannel(clientFd)

        private val upstreamFd: Int = newUpstream(serverPort)
        private val upstreamChannel = IoUringUdpChannel(upstreamFd)

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

        /** Per-direction index + held-datagram slot + mechanics. [send] forwards [n] bytes from a buffer. */
        private inner class DirectionPump(
            private val direction: ImpairDirection,
            private val send: suspend (PlatformBuffer, Int) -> Unit,
        ) {
            private val index = AtomicInt(0)
            private var held: PlatformBuffer? = null
            private var heldLen = 0

            suspend fun handle(
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

        private val c2sPump = DirectionPump(ImpairDirection.ClientToServer) { b, n -> upstreamChannel.send(b, n, dest = null) }
        private val s2cPump =
            DirectionPump(ImpairDirection.ServerToClient) { b, n ->
                if (clientAddrLen.value > 0) {
                    clientChannel.sendTo(b, n, clientAddr.ptr.reinterpret(), clientAddrLen.value.convert())
                }
            }

        private val c2sJob = scope.launch { clientToServerLoop() }
        private val s2cJob = scope.launch { serverToClientLoop() }

        /** Client → server: capture the client source on the first packet, then apply the policy. */
        private suspend fun clientToServerLoop() {
            val buf = bufferFactory.allocate(MAX_DATAGRAM)
            try {
                while (running.value == 1) {
                    try {
                        val r = clientChannel.recvFrom(buf)
                        if (running.value == 0) break // fd closed on teardown — recv returned an error CQE
                        if (r.bytesReceived <= 0) continue
                        if (clientAddrLen.value == 0 && r.peerAddrLen.toInt() > 0) {
                            memcpy(clientAddr.ptr, r.peerAddr, r.peerAddrLen.convert())
                            clientAddrLen.value = r.peerAddrLen.toInt() // publish after the copy
                        }
                        c2sPump.handle(buf, r.bytesReceived)
                    } catch (e: Throwable) {
                        if (running.value == 0) break
                        throw e
                    }
                }
            } finally {
                buf.freeNativeMemory()
            }
        }

        /** Server → client: apply the policy and forward to the captured client source. */
        private suspend fun serverToClientLoop() {
            val buf = bufferFactory.allocate(MAX_DATAGRAM)
            try {
                while (running.value == 1) {
                    try {
                        // -ETIMEDOUT (idle) or -ECANCELED/-EBADF (fd closed on teardown) → re-check/re-read.
                        val n = upstreamChannel.receive(buf)
                        if (running.value == 0) break
                        if (n <= 0) continue
                        s2cPump.handle(buf, n)
                    } catch (e: Throwable) {
                        if (running.value == 0) break
                        throw e
                    }
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
            val srcPtr = src.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
            val dstPtr = dst.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
            memcpy(dstPtr, srcPtr, n.convert())
            return dst
        }

        override suspend fun close() {
            // Tear down WITHOUT the submitAndWait cancellation path: close the fds so the in-flight
            // recvFrom/receive complete via an error CQE (the kernel releases the recv buffers FIRST),
            // let the loops exit on the `running` flag, and only THEN free the channel's recv structures.
            // Freeing them while a recv is still in flight is a use-after-free that glibc aborts on
            // ("malloc(): unsorted double linked list corrupted") — see IoUringUdpServerChannel.closeFd.
            running.value = 0
            clientChannel.closeFd() // unblock the c2s recvFrom (error CQE) — does NOT free recv structs yet
            close(upstreamFd) // unblock the s2c receive
            c2sJob.join() // loops exit on `running` after their recv completes; each frees its own recv buffer
            s2cJob.join()
            supervisor.cancelAndJoin() // cancel any in-flight delayed-send children (the loops are already done)
            c2sPump.freeHeld()
            s2cPump.freeHeld()
            clientChannel.freeBuffers() // NOW safe — the receive loop has fully exited
            nativeHeap.free(clientAddr)
        }

        companion object {
            private const val MAX_DATAGRAM = 2048

            private fun newUpstream(serverPort: Int): Int {
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
