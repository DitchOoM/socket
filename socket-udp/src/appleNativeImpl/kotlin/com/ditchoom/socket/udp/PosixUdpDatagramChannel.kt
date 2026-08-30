@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    // CloseableCoroutineDispatcher is experimental, and recvDispatcher is exposed (internal) to the #498 witness.
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramCloseReason
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.HopLimit
import com.ditchoom.buffer.flow.LocalAddress
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.MSG_DONTWAIT
import platform.posix.POLLIN
import platform.posix.close
import platform.posix.errno
import platform.posix.memset
import platform.posix.pipe
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.write

/**
 * Apple (K/N) [AddressedDatagramChannel] backed by blocking POSIX `recvfrom`/`sendto` — the lift
 * of the quiche `AppleUdpServerChannel`, reshaped to the public datagram trichotomy. Per-packet source
 * is recovered from `recvfrom` and surfaced as [Datagram.peer]; every send names its destination (the
 * addressed refinement requires `to`, so a destination-less send is unrepresentable); the quiche
 * `lastDest` cache is dropped (the send target materializes from [SocketAddress] primitives into a
 * `memScoped` scratch, RFC §4). [localAddress] is plainly non-null: `UdpSocket.bind` fails fast on a
 * getsockname failure before constructing this channel.
 *
 * ## Why nothing here closes a descriptor another party can still reach (#498, #507)
 *
 * The blocking syscalls run on a dedicated single-thread dispatcher, so a [close] concurrent with a
 * [receive] has three things to get right: the dispatcher must not be closed under a hop, the socket
 * descriptor must not be closed under a syscall, and a parked receiver must still be woken. All three
 * are one question — *who is genuinely last out* — and [LastOutHandoff] answers it in a single CAS.
 *
 *  - Every party that touches the descriptor is admitted first: [receive] and [send] via
 *    [LastOutHandoff.enter], [close] via [LastOutHandoff.close], which counts the closer in for the
 *    same reason (it has to reach the wake pipe). Each leaves through [LastOutHandoff.exit], and the
 *    one that empties a closed word releases *everything* — socket, both pipe ends, dispatcher.
 *  - [close] therefore never closes the socket. Waking a parked receiver was the only reason it ever
 *    did (#498's ordering), and that job now belongs to the [WakePipe]: the receive loop `poll`s both
 *    descriptors and calls `recvfrom` only when the *socket* is readable, and [close] writes one byte
 *    to the pipe. A receiver woken by that byte returns [DatagramReadResult.Closed] —
 *    never a datagram, and never a `recvfrom` on a number some later `socket()`/`open()` has recycled
 *    (#507: it read another socket's datagram, delivered as a valid-looking `Received`).
 *  - Darwin leaves no cheaper wake: `shutdown()` on an *unconnected* UDP socket is `ENOTCONN`, so the
 *    usual "shutdown to wake, close later" ordering does not apply here.
 *
 * The wake byte is never read back. One byte written once (the CAS admits exactly one closer) leaves
 * the read end permanently readable, so every receiver inside `poll` — and any that reaches it later,
 * before its own [LastOutHandoff.enter] is refused — sees the close, level-triggered, with no timed
 * window and no second latch.
 *
 * The recv sockaddr scratch is per-call (`memScoped`), so a concurrent [close] never races a shared
 * write buffer. Not thread-safe: confine [receive]/[send] each to one coroutine (buffer-flow contract).
 *
 * Control plane: the rich Darwin POSIX ceiling (`IP_TOS`/`IP_DONTFRAG`/`IP_RECVTOS`/`IP_PKTINFO`) is a
 * labeled follow-up (#377); this first landing advertises [DatagramCapabilities.None] (honest — the datapath
 * uses plain `recvfrom`/`sendto` with no ancillary data), so every read field is its typed absent
 * state and every advisory send field a no-op.
 *
 * @param beforeDispatch Test seam for the #498/#507 window: runs on every receive iteration after this
 *   receiver has been admitted and before it hops onto [recvDispatcher]. Production leaves the no-op
 *   default; `PosixUdpReceiveCloseHandoffTests` and `PosixUdpCloseNeverUnderAReaderTests` park a
 *   receiver here and run `close()` around it.
 */
@ExperimentalDatagramApi
internal class PosixUdpDatagramChannel(
    private val fd: Int,
    override val localAddress: SocketAddress,
    private val receiveBufferSize: Int = MAX_UDP_PAYLOAD,
    private val bufferFactory: BufferFactory = BufferFactory.deterministic(),
    private val beforeDispatch: suspend () -> Unit = {},
) : AddressedDatagramChannel {
    /** Who releases the descriptors and the dispatcher — the last party out — in one CAS; see [LastOutHandoff]. */
    private val handoff = LastOutHandoff()

    // Declared before recvDispatcher on purpose: a pipe() this process cannot satisfy must not leave a
    // dispatcher thread running behind a constructor that never returns.
    private val wake = WakePipe.openOrClose(fd)

    /** `internal` only so the #498 witness can prove it is closed once the channel is; not an API. */
    internal val recvDispatcher = newSingleThreadContext("apple-udp-recv-$fd")

    override val isOpen: Boolean get() = !handoff.closed

    override val maxWritableSize: Int = MAX_UDP_PAYLOAD

    // Not DatagramCapabilities.None: `None` asserts requiresNativeMemoryBuffers = false, and `sendto`
    // takes a raw base pointer (send errors outright if payload.nativeMemoryAccess is absent). No
    // control plane otherwise — this path uses plain recvfrom/sendto with no ancillary data.
    override val capabilities: DatagramCapabilities = DatagramCapabilities(requiresNativeMemoryBuffers = true)

    override suspend fun receive(): DatagramReadResult {
        val payload = bufferFactory.allocate(receiveBufferSize)
        val ptr = payload.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        try {
            while (true) {
                // Admission, not a flag read: the CAS that observes "open" also counts this receiver
                // in, so close() sees it and hands the release to it instead of racing it to the
                // descriptor it is about to call.
                when (handoff.enter()) {
                    LastOutHandoff.Admission.Refused -> {
                        payload.freeNativeMemory()
                        return DatagramReadResult.Closed()
                    }
                    LastOutHandoff.Admission.Admitted -> Unit
                }
                val outcome: DatagramReadResult? =
                    try {
                        beforeDispatch()
                        memScoped {
                            val addr = alloc<sockaddr_storage>()
                            val addrLen = alloc<UIntVar>() // socklen_t is uint32 on Darwin (no socklen_tVar alias)
                            val watched = allocArray<pollfd>(WATCHED_DESCRIPTORS)
                            watched[SOCKET_SLOT].fd = fd
                            watched[SOCKET_SLOT].events = POLLIN.convert()
                            watched[WAKE_SLOT].fd = wake.readEnd
                            watched[WAKE_SLOT].events = POLLIN.convert()
                            memset(addr.ptr, 0, sizeOf<sockaddr_storage>().convert())
                            addrLen.value = sizeOf<sockaddr_storage>().convert()
                            val step =
                                withContext(recvDispatcher) {
                                    pollThenReceive(watched, ptr, payload.capacity, addr.ptr.reinterpret(), addrLen)
                                }
                            when (step) {
                                is Step.Woken -> DatagramReadResult.Closed()
                                // Negated: buffer-flow's OsError carries a *negative* errno (that is what
                                // the Linux backend's io_uring `res` already is). Darwin's `recvfrom`
                                // returns a bare -1, which names nothing, so the errno is read instead.
                                is Step.Failed -> DatagramReadResult.Closed(DatagramCloseReason.OsError(-step.errno))
                                is Step.Delivered -> {
                                    val peer = sockaddrToAppleSocketAddress(addr.ptr.reinterpret<sockaddr>())
                                    if (peer == null) {
                                        null // unroutable family — skip, keep waiting
                                    } else {
                                        payload.position(0)
                                        payload.setLimit(step.bytes)
                                        // All five args explicit: a defaulted localAddress rides the
                                        // default-args bridge and boxes the value class (LocalAddress KDoc);
                                        // no ancillary data on this datapath, so all typed absent states.
                                        DatagramReadResult.Received(
                                            Datagram(
                                                payload = payload,
                                                peer = peer,
                                                ecn = Ecn.Unknown,
                                                localAddress = LocalAddress.Unknown,
                                                hopLimit = HopLimit.Unknown,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    } finally {
                        leave()
                    }
                if (outcome is DatagramReadResult.Received) return outcome
                if (outcome is DatagramReadResult.Closed) {
                    payload.freeNativeMemory()
                    return outcome
                }
                // outcome == null → retry with the same payload buffer.
            }
        } catch (t: Throwable) {
            payload.freeNativeMemory()
            throw t
        }
    }

    /**
     * What one trip through `poll` + `recvfrom` produced. A sealed answer rather than a signed count:
     * "woken by the close" and "the syscall failed" are different ends, and neither is a byte count.
     */
    private sealed interface Step {
        /** [bytes] of datagram landed in the payload, and the sockaddr scratch names its sender. */
        data class Delivered(
            val bytes: Int,
        ) : Step

        /** The wake pipe fired: [close] ran, and this receiver returns closed without a datagram. */
        data object Woken : Step

        /** A syscall failed with this (positive) `errno`; the receive ends. */
        data class Failed(
            val errno: Int,
        ) : Step
    }

    /**
     * Waits for the socket or the wake pipe, and reads at most one datagram. Runs on [recvDispatcher];
     * every descriptor it names is one this caller was admitted to touch.
     *
     * The wake slot is consulted first, so a close always wins a race with an arriving datagram — a
     * receiver woken by [close] reports closed and never a datagram it would have to hand back. The
     * `recvfrom` carries `MSG_DONTWAIT` even though `poll` just said the socket is readable: if
     * anything drained the socket in between, the read must return to `poll` (where the wake can reach
     * it) rather than block in a syscall nothing can interrupt. `EINTR` is retried on both calls.
     */
    private fun pollThenReceive(
        watched: CArrayPointer<pollfd>,
        payload: CPointer<ByteVar>,
        capacity: Int,
        addr: CPointer<sockaddr>,
        addrLen: UIntVar,
    ): Step {
        while (true) {
            watched[SOCKET_SLOT].revents = 0
            watched[WAKE_SLOT].revents = 0
            if (poll(watched, WATCHED_DESCRIPTORS.convert(), NO_TIMEOUT) < 0) {
                val code = errno
                if (code == EINTR) continue
                return Step.Failed(code)
            }
            if (watched[WAKE_SLOT].revents.toInt() != 0) return Step.Woken
            // Any event on the socket — readable, or an error condition poll reports unasked — is a
            // question only the syscall can answer, and its errno is the honest reason.
            if (watched[SOCKET_SLOT].revents.toInt() == 0) continue
            addrLen.value = sizeOf<sockaddr_storage>().convert()
            val n = recvfrom(fd, payload, capacity.convert(), MSG_DONTWAIT, addr, addrLen.ptr).toInt()
            if (n >= 0) return Step.Delivered(n)
            val code = errno
            // EAGAIN here means the readability poll reported is already gone: wait for it again.
            if (code == EINTR || code == EAGAIN || code == EWOULDBLOCK) continue
            return Step.Failed(code)
        }
    }

    /**
     * This party's departure. If it was the last one out of a closed channel, it releases the
     * descriptors and the dispatcher — and closes the dispatcher off its own thread, because
     * `MultiWorkerDispatcher.close()` joins its worker, and a caller that resumed *on* that worker
     * (only a `Dispatchers.Unconfined` caller can) would otherwise wait on itself. [NonCancellable]
     * because the usual reason control is here at all is the caller's cancellation, and a cancelled hop
     * would skip the release and leak the worker thread and three descriptors. A failing close
     * propagates: it is the caller's end-of-life report, not noise.
     */
    private suspend fun leave() {
        when (handoff.exit()) {
            LastOutHandoff.Departure.NotLast -> Unit
            LastOutHandoff.Departure.LastOut -> withContext(NonCancellable + Dispatchers.Default) { releaseAll() }
        }
    }

    /**
     * Closes the socket, both wake-pipe ends and the dispatcher. Reached by exactly one party — the CAS
     * that lands the word on closed-and-empty — so nothing else can be inside a syscall on any of them.
     */
    private fun releaseAll() {
        close(fd)
        close(wake.readEnd)
        close(wake.writeEnd)
        recvDispatcher.close()
    }

    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions,
    ) {
        // A sender touches the same descriptor a receiver does, so it is admitted the same way: the CAS
        // that says "still open" is what keeps close() from releasing fd under this sendto.
        when (handoff.enter()) {
            LastOutHandoff.Admission.Refused -> error("sink is closed")
            LastOutHandoff.Admission.Admitted -> Unit
        }
        try {
            val access = payload.nativeMemoryAccess ?: error("send requires a native-memory buffer")
            val ptr = (access.nativeAddress + payload.position()).toCPointer<ByteVar>()!!
            val len = payload.remaining()
            // Parity guard: the same condition reports the same typed reason on every backend.
            if (len > maxWritableSize) throw DatagramSendException(DatagramSendError.TooLarge(len, maxWritableSize))
            memScoped {
                val addr = alloc<sockaddr_storage>()
                val addrLen = to.writeSockaddr(addr)
                // Check the result. A discarded `sendto` return is a datagram that vanishes between a
                // clean return and the wire — for quiche, a packet its congestion controller counts as
                // in flight but which never left the host.
                val sent = sendto(fd, ptr, len.convert(), 0, addr.ptr.reinterpret(), addrLen).toLong()
                if (sent < 0) throw DatagramSendException(sendErrnoToError(attempted = len, limit = maxWritableSize))
            }
        } finally {
            leave()
        }
    }

    /**
     * Refuses every further party, wakes the ones already inside, and leaves like any of them. It does
     * **not** close the socket: waking is the pipe's job now, so the only party that closes a
     * descriptor is whichever one [leave] finds to be last out — possibly this one.
     */
    override fun close() {
        when (handoff.close()) {
            LastOutHandoff.Closing.AlreadyClosed -> Unit
            LastOutHandoff.Closing.Admitted -> {
                try {
                    wake.signal()
                } finally {
                    when (handoff.exit()) {
                        LastOutHandoff.Departure.NotLast -> Unit
                        // Nobody else is inside, so nothing is on the dispatcher and this call may join
                        // its worker directly. Not wrapped — a failing close is reported, not lost.
                        LastOutHandoff.Departure.LastOut -> releaseAll()
                    }
                }
            }
        }
    }

    /**
     * A channel's self-pipe: the wake that lets [close] leave the socket descriptor alone. Both ends
     * are released by the last party out, exactly like the socket.
     */
    private class WakePipe(
        val readEnd: Int,
        val writeEnd: Int,
    ) {
        /**
         * Makes [readEnd] readable, permanently, for every receiver inside `poll`. One byte, written by
         * the one closer the CAS admits, and never read back — so the wake is level-triggered and a
         * receiver that reaches `poll` after it still sees it. `EINTR` is retried; nothing else can
         * happen here (the read end is open, this party being counted in is what keeps it open, and a
         * single byte cannot fill a pipe), so a failure is reported rather than swallowed into a
         * receiver that would park forever.
         */
        fun signal() {
            memScoped {
                val byte = alloc<ByteVar>()
                byte.value = 0
                while (true) {
                    if (write(writeEnd, byte.ptr, 1.convert()).toInt() >= 0) return
                    val code = errno
                    if (code == EINTR) continue
                    error("write() to the receive wake pipe failed: errno $code")
                }
            }
        }

        companion object {
            /**
             * Opens the pipe, or closes [socketFd] and throws. The channel has taken ownership of that
             * descriptor by the time this runs, so a construction that cannot complete must not leak it.
             */
            fun openOrClose(socketFd: Int): WakePipe =
                memScoped {
                    val ends = allocArray<IntVar>(2)
                    if (pipe(ends) != 0) {
                        val code = errno
                        close(socketFd)
                        error("pipe() for the receive wake failed: errno $code")
                    }
                    WakePipe(readEnd = ends[0], writeEnd = ends[1])
                }
        }
    }

    companion object {
        private const val MAX_UDP_PAYLOAD = 65507

        /** `poll` slots: the socket first, its wake second — and the wake is read first. */
        private const val SOCKET_SLOT = 0
        private const val WAKE_SLOT = 1
        private const val WATCHED_DESCRIPTORS = 2

        /** `poll` blocks until one of the two speaks; the wake is what ends the wait, not a deadline. */
        private const val NO_TIMEOUT = -1
    }
}
