@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import platform.posix.close
import platform.posix.sockaddr_in
import kotlin.concurrent.AtomicInt

/**
 * Shared client ↔ proxy ↔ server UDP forwarder for the Apple K/Native members of
 * [QuicImpairmentTestSuite] and [QuicPassiveMigrationTestSuite].
 *
 * Linux builds both of its proxies on the repo's io_uring primitives, which are linuxMain-only; Darwin
 * has none, so this is two blocking `recvfrom` pump loops, each confined to its own single-thread
 * dispatcher — the shape `:socket-udp`'s Apple `PosixUdpDatagramChannel` uses in production. Unlike the
 * two Linux files (which each carry their own copy), the client-source capture, the upstream swap and
 * the teardown ordering are factored here: they are the parts that are fiddly and identical, and the
 * subclasses differ only in what they do with a datagram.
 *
 * Datagrams are handed to the subclass as a **copy** ([ByteArray] — tests may allocate those freely).
 * The recv scratch is reused across iterations, so anything the subclass holds past the next receive
 * (a delayed or reordered datagram) would otherwise alias it; copying once removes that whole class of
 * bug at a cost that is irrelevant at loopback test volumes.
 *
 * Teardown order is load-bearing and mirrors the Linux proxies' hard-won ordering: stop the loops
 * first ([running] = 0, then close the fds so a parked receive returns), join them, and only then
 * cancel any child sends and free the pinned client address.
 */
internal abstract class ApplePosixUdpForwarder(
    private val serverPort: Int,
) {
    private val clientFd: Int = ApplePosixUdp.openBoundLoopbackSocket()

    /**
     * The local port the client connects to (the proxy's client-facing socket). Named `boundPort`, not
     * `proxyPort`, so a subclass can satisfy `ImpairingProxy`/`RebindingProxy`'s abstract `proxyPort`
     * with an explicit one-line override rather than relying on an inherited-concrete-implements-abstract
     * resolution.
     */
    val boundPort: Int = ApplePosixUdp.boundPortOf(clientFd)

    // Server-facing socket; swapped by [swapUpstream] for the NAT-rebind test. AtomicInt so both pump
    // threads see the swap.
    private val upstreamFd = AtomicInt(ApplePosixUdp.openConnectedLoopbackSocket(serverPort))

    // Pinned copy of the client's source address: the c2s loop fills it, the s2c loop replies to it.
    private val clientAddr = nativeHeap.alloc<sockaddr_in>()
    private val clientAddrKnown = AtomicInt(0)

    /** 1 while the pump loops should keep running; teardown flips it to 0. */
    protected val running = AtomicInt(1)

    private val supervisor = SupervisorJob()

    /** Scope for subclass-launched child sends (e.g. a delayed forward). Cancelled after the loops stop. */
    protected val scope = CoroutineScope(supervisor + kotlinx.coroutines.Dispatchers.Default)

    private val c2sDispatcher = newSingleThreadContext("apple-udp-proxy-c2s-$boundPort")
    private val s2cDispatcher = newSingleThreadContext("apple-udp-proxy-s2c-$boundPort")

    private var c2sJob: Job? = null
    private var s2cJob: Job? = null

    init {
        ApplePosixUdp.setReceiveTimeout(clientFd, RECV_TIMEOUT_MILLIS)
        ApplePosixUdp.setReceiveTimeout(upstreamFd.value, RECV_TIMEOUT_MILLIS)
    }

    /**
     * Begin pumping. Called by the subclass at the END of its own construction, never from this `init`:
     * the loops immediately invoke [onClientToServer] / [onServerToClient], and launching them from a
     * base-class initializer would run subclass code against subclass fields that are still null.
     */
    protected fun start() {
        c2sJob = scope.launch { clientToServerLoop() }
        s2cJob = scope.launch { serverToClientLoop() }
    }

    /** Handle one client→server datagram (already copied out of the recv scratch). */
    protected abstract suspend fun onClientToServer(datagram: ByteArray)

    /** Handle one server→client datagram (already copied out of the recv scratch). */
    protected abstract suspend fun onServerToClient(datagram: ByteArray)

    /** Forward [datagram] (first [length] bytes) to the server over the current upstream socket. */
    protected fun forwardUpstream(
        datagram: ByteArray,
        length: Int = datagram.size,
    ) {
        ApplePosixUdp.sendConnected(upstreamFd.value, datagram, length)
    }

    /** Forward [datagram] (first [length] bytes) back to the client source captured by the c2s loop. */
    protected fun forwardToClient(
        datagram: ByteArray,
        length: Int = datagram.size,
    ) {
        if (clientAddrKnown.value == 1) ApplePosixUdp.sendTo(clientFd, datagram, length, clientAddr)
    }

    /**
     * Swap the upstream socket for one with a fresh source port — the NAT rebind. The new socket is
     * opened BEFORE the old one is closed so the two never share an fd number, then closing the old fd
     * unblocks the parked s2c receive, which simply re-reads [upstreamFd] and continues.
     */
    protected fun swapUpstream() {
        val old = upstreamFd.value
        val fresh = ApplePosixUdp.openConnectedLoopbackSocket(serverPort)
        ApplePosixUdp.setReceiveTimeout(fresh, RECV_TIMEOUT_MILLIS)
        upstreamFd.value = fresh
        close(old)
    }

    private suspend fun clientToServerLoop() {
        val scratch = ByteArray(ApplePosixUdp.MAX_DATAGRAM)
        val peer = nativeHeap.alloc<sockaddr_in>()
        try {
            while (running.value == 1) {
                val n = withContext(c2sDispatcher) { ApplePosixUdp.receiveFrom(clientFd, scratch, peer) }
                if (running.value == 0) break
                if (n <= 0) continue // receive timeout tick, or the fd was closed on teardown
                if (clientAddrKnown.value == 0) {
                    ApplePosixUdp.copyAddr(from = peer, to = clientAddr)
                    clientAddrKnown.value = 1 // publish only after the copy is complete
                }
                onClientToServer(scratch.copyOf(n))
            }
        } finally {
            nativeHeap.free(peer)
        }
    }

    private suspend fun serverToClientLoop() {
        val scratch = ByteArray(ApplePosixUdp.MAX_DATAGRAM)
        while (running.value == 1) {
            // A receive timeout, or -1 because [swapUpstream]/teardown closed the fd: re-read the
            // current upstream and carry on.
            val n = withContext(s2cDispatcher) { ApplePosixUdp.receiveFrom(upstreamFd.value, scratch) }
            if (running.value == 0) break
            if (n <= 0) continue
            onServerToClient(scratch.copyOf(n))
        }
    }

    /** Stop the pump loops and release every socket, thread and pinned allocation. */
    protected open suspend fun shutdown() {
        running.value = 0
        close(clientFd) // unblock a parked c2s receive
        close(upstreamFd.value) // unblock a parked s2c receive
        c2sJob?.join()
        s2cJob?.join()
        supervisor.cancelAndJoin() // only now: cancels any in-flight delayed sends the subclass launched
        runCatching { c2sDispatcher.close() }
        runCatching { s2cDispatcher.close() }
        nativeHeap.free(clientAddr)
    }

    companion object {
        /**
         * Receive-timeout tick. Closing the fd is what normally wakes a parked `recvfrom`; this bounds
         * the wait if it ever doesn't, so a teardown bug fails the suite instead of hanging it.
         */
        private const val RECV_TIMEOUT_MILLIS = 100L
    }
}
