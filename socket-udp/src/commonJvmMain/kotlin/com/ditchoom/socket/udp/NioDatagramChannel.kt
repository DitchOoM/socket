@file:OptIn(InternalDatagramLoopApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.EcnPreference
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.HopLimit
import com.ditchoom.buffer.flow.LocalAddress
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.unwrapFully
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketOption
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import java.nio.channels.DatagramChannel as NioChannel

/** 65535 − 8 (UDP header) − 20 (IPv4 header). Large enough that no real datagram is truncated. */
private const val MAX_UDP_PAYLOAD = 65507

/** How long a send waits out a full output buffer before reporting [DatagramSendError.WouldBlock]. */
private val SEND_BACKPRESSURE_BUDGET = 1.seconds

/**
 * The outcome of waiting for a full send buffer to drain — exhaustive, so the send loop's three
 * continuations (retry / give up / unwind) are the compiler's business rather than a boolean's.
 */
internal sealed interface WriteReadiness {
    /** The socket reported it can accept a datagram again; retry the same view. */
    data object Writable : WriteReadiness

    /** The remaining budget elapsed with the socket still full — the caller's [DatagramSendError.WouldBlock]. */
    data object TimedOut : WriteReadiness

    /** The channel (or its selector) was closed while waiting; this send can never complete. */
    data object Closed : WriteReadiness
}

/**
 * Write [view] with [write], treating a zero return as backpressure rather than as a drop.
 *
 * Extracted from the channel — and taking the writer as a parameter — so the retry, give-up and
 * error-mapping paths are reachable from a test with a stub writer. A real socket cannot be asked to
 * refuse a datagram on demand: on loopback the sender's queue never fills, because packets move
 * straight to the receiver. Forcing it would need a rate-limited link in a network namespace, which
 * would test the kernel rather than this loop, only on Linux, and only with privileges.
 *
 * A refusal is waited out through [awaitWritable] — the socket's own writability signal
 * ([NioDatagramChannelCore.awaitWritable] registers `OP_WRITE` on a selector and parks until the
 * kernel says there is room again). It is a parameter for the same reason [write] is: the stub in
 * `BackpressureLoopTests` drives every branch, including the one where readiness never arrives, in
 * microseconds and without a socket.
 *
 * [budget] bounds the *whole* send, measured against a monotonic mark rather than by accumulating
 * sleeps, and each wait is handed only the time left in it — so a send still reports
 * [DatagramSendError.WouldBlock] on that deadline, and the caller's buffer is left unconsumed either
 * way.
 *
 * An [IOException] from [write] is classified by [jvmSendErrorOf] — the JDK's exception types and
 * `strerror` phrases onto the same sealed set every other backend reports — with [limit] (the
 * channel's advertised ceiling) carried into [DatagramSendError.TooLarge] as `sendErrnoToError` does.
 */
internal suspend fun writeAbsorbingBackpressure(
    view: ByteBuffer,
    write: (ByteBuffer) -> Int,
    awaitWritable: suspend (Duration) -> WriteReadiness,
    budget: Duration = SEND_BACKPRESSURE_BUDGET,
    limit: Int = MAX_UDP_PAYLOAD,
) {
    val length = view.remaining()
    val started = TimeSource.Monotonic.markNow()
    while (true) {
        val written =
            try {
                write(view)
            } catch (e: IOException) {
                // The JDK has already reduced the errno to a type or a strerror phrase; classify it
                // here, at the boundary, so a consumer branches on the member and never on the cause.
                throw DatagramSendException(jvmSendErrorOf(e, attempted = length, limit = limit))
            }
        if (written > 0) return
        check(view.remaining() == length) { "a zero-length datagram write must not consume the view" }
        val remaining = budget - started.elapsedNow()
        if (remaining <= Duration.ZERO) throw DatagramSendException(DatagramSendError.WouldBlock)
        when (awaitWritable(remaining)) {
            // The socket has room again: retry the same view. Exactly one write per readiness
            // signal — no timer, no re-probe of a socket that has not said anything.
            WriteReadiness.Writable -> Unit
            WriteReadiness.TimedOut -> throw DatagramSendException(DatagramSendError.WouldBlock)
            // Closed underneath us: the same typed shape a mid-send close already reports, with the
            // JVM exception carrying the detail rather than a message we invented.
            WriteReadiness.Closed -> throw DatagramSendException(DatagramSendError.Transport(ClosedChannelException()))
        }
    }
}

/**
 * JVM/Android [DatagramChannel] machinery backed by a NIO [NioChannel] — the real-socket
 * lift of the quiche `NioUdpChannel`, cleaned to the public datagram shape (RFC §7):
 *
 * - **per-packet source exposed** — `NioUdpChannel.receive` returned only a length and threw the sender
 *   away; here [receive] returns the source via [NioChannel.receive] and surfaces it as [Datagram.peer].
 * - **1-entry `lastDest` cache dropped** — an addressed send extracts the destination's owned
 *   [InetSocketAddress] with a field read ([SocketAddress.toInetSocketAddress]); no reconstruction to
 *   amortize (RFC §4).
 * - **control plane wired to the platform ceiling** — ECN/DSCP via `IP_TOS` and DF via `IP_DONTFRAGMENT`
 *   where [NioChannel.supportedOptions] offers them; capabilities are computed from that set (§7.1),
 *   never assumed. NIO cannot read receive-side ancillary data, so all read-side control fields degrade
 *   to their §7.2 typed absent states.
 *
 * The addressing mode is fixed at construction, in the type: [ConnectedNioDatagramChannel] (fixed peer,
 * no destination parameter) vs [AddressedNioDatagramChannel] (every send names its destination). This
 * core holds only the mode-agnostic machinery — receive loop, control-plane appliers, lifecycle — and
 * deliberately implements neither send refinement.
 *
 * Cancellation-correct: the channel is non-blocking, and a receive or a full send waits for readiness
 * in one of two places, both cancellable without closing the socket (a blocking `receive()` would
 * `ClosedByInterruptException` it):
 *
 * - On a [DatagramEventLoop] (the coroutine is dispatched by one), in that loop's own selector, which
 *   multiplexes every channel the loop serves. No thread is parked for this channel, and readiness
 *   resumes the waiter on the thread that observed it.
 * - Anywhere else, in the selector of the [DatagramEventLoop] this channel is homed on ([udpLoopGroup]):
 *   the wait hops to that loop's thread and back. A `Selector` is only ever selected by the platform
 *   thread of the loop that owns it: never on `Dispatchers.IO` (64 parked receives exhaust it) and never
 *   on a virtual thread (a virtual thread in `select()` holds its carrier).
 *
 * Not thread-safe; confine [receive] and `send` each to one coroutine, per the buffer-flow contract.
 */
@ExperimentalDatagramApi
internal abstract class NioDatagramChannelCore(
    protected val channel: NioChannel,
    private val receiveBufferSize: Int = MAX_UDP_PAYLOAD,
    private val bufferFactory: BufferFactory = BufferFactory.Default,
) : DatagramChannel {
    /** The loop this channel waits on when the caller is not on a loop; chosen on first use. */
    private val home: DatagramEventLoop by lazy { udpLoopGroup.next() }

    /** The loops this channel has waited on, each told by [close] to wake whatever still waits there. */
    private val loops = CopyOnWriteArraySet<DatagramEventLoop>()

    @Volatile
    private var closed = false

    override val isOpen: Boolean get() = !closed && channel.isOpen

    /** The classic UDP payload ceiling (65535 − 8 UDP − 20 IP). Path-MTU/PMTUD is a consumer concern. */
    override val maxWritableSize: Int = MAX_UDP_PAYLOAD

    // Control-plane options resolved reflectively from what THIS socket actually supports
    // (DatagramChannel.supportedOptions(), the §7.1 matrix). Matching by name keeps
    // commonJvmMain free of a hard compile-time dependency on JDK-19+ / Android-version-specific option
    // constants, so the shared source set compiles and degrades correctly on every JVM and Android level.
    private val supportedOptions: Set<SocketOption<*>> = channel.supportedOptions()

    private fun optionNamed(name: String): SocketOption<*>? = supportedOptions.firstOrNull { it.name() == name }

    private val ipTosOption: SocketOption<*>? = optionNamed("IP_TOS")
    private val dontFragmentOption: SocketOption<*>? = optionNamed("IP_DONTFRAGMENT")

    override val capabilities: DatagramCapabilities =
        DatagramCapabilities(
            ecnSend = ipTosOption != null,
            ecnReceive = false, // NIO exposes no receive-side ancillary data (no recv cmsg)
            dscpSend = ipTosOption != null,
            dontFragment = dontFragmentOption != null,
            hopLimitSend = false, // NIO has no unicast TTL option (only IP_MULTICAST_TTL)
            hopLimitReceive = false,
            localAddressReceive = false, // no IP_PKTINFO on NIO
            sourceAddressSelect = false,
            multicast = false, // design-for, defer to Phase 5
            // NIO's DatagramChannel.send accepts a heap ByteBuffer and copies it into a direct buffer
            // internally, so a heap payload is slower here but never fatal. An affirmative false.
            requiresNativeMemoryBuffers = false,
        )

    // IP_TOS / IP_DONTFRAGMENT are socket-wide on NIO (there is no per-datagram ancillary send path), so
    // apply only on change to avoid a redundant setsockopt on every send.
    private var appliedTos = Int.MIN_VALUE
    private var appliedDontFragment = false

    private fun applyControlPlane(options: DatagramSendOptions) {
        val tosOpt = ipTosOption
        if (tosOpt != null && (options.ecn != EcnPreference.OsDefault || options.dscp >= 0)) {
            val dscpBits = if (options.dscp >= 0) options.dscp else 0
            val ecnBits = if (options.ecn != EcnPreference.OsDefault) options.ecn.codepoint else 0
            val tos = (dscpBits shl 2) or ecnBits
            if (tos != appliedTos) {
                @Suppress("UNCHECKED_CAST")
                runCatching { channel.setOption(tosOpt as SocketOption<Int>, tos) }.onSuccess { appliedTos = tos }
            }
        }
        val dfOpt = dontFragmentOption
        if (dfOpt != null && options.dontFragment != appliedDontFragment) {
            @Suppress("UNCHECKED_CAST")
            runCatching { channel.setOption(dfOpt as SocketOption<Boolean>, options.dontFragment) }
                .onSuccess { appliedDontFragment = options.dontFragment }
        }
    }

    /** Off the Android main thread, where even a non-blocking `recvfrom` is a violation: see [offTheAndroidMainThread]. */
    override suspend fun receive(): DatagramReadResult =
        offTheAndroidMainThread(onAbandoned = { (it as? DatagramReadResult.Received)?.datagram?.payload?.freeIfNeeded() }) {
            receiveInPlace()
        }

    private suspend fun receiveInPlace(): DatagramReadResult {
        while (true) {
            if (closed) return DatagramReadResult.Closed()
            var payload: PlatformBuffer? = null
            // Ownership of `payload` transfers to the caller only on the Received path. Tracking that
            // explicitly, and freeing in `finally` otherwise, is what makes the staging buffer safe on
            // *every* exit: the two Closed arms, a spurious wakeup's `continue`, cancellation, and any
            // other IOException from channel.receive() (PortUnreachableException,
            // ENETUNREACH/EHOSTUNREACH/ECONNABORTED when a network is torn down under the socket) —
            // without it, that last path drops one receiveBufferSize pooled buffer per throw, at the
            // driver's retry rate.
            var handedOff = false
            try {
                // The read below may not suspend at all, so it is not the cancellation point the wait
                // always was: check here, as entering the wait did.
                currentCoroutineContext().ensureActive()
                val buffer = bufferFactory.allocate(receiveBufferSize)
                payload = buffer
                val byteBuffer = (buffer.unwrapFully() as BaseJvmBuffer).byteBuffer
                byteBuffer.clear()
                // The channel is non-blocking, so read first: a datagram already queued (the common case
                // under load) costs one syscall and no wait. Only an empty socket waits for readiness.
                // Both modes receive via channel.receive(): on a connected socket the reported sender IS
                // the connected peer (value-equal via InternedJvmSocketAddress).
                val sender = channel.receive(byteBuffer) as InetSocketAddress?
                if (sender == null) {
                    // Give the staging buffer back while waiting: a wait can last the connection's life.
                    payload = null
                    buffer.freeNativeMemory()
                    awaitReadable()
                    continue // re-check closed, then read what woke us
                }
                val length = byteBuffer.position()
                // Expose exactly the received datagram as the readable window [0, length).
                buffer.position(0)
                buffer.setLimit(length)
                handedOff = true
                return DatagramReadResult.Received(
                    Datagram(
                        payload = buffer,
                        peer = InternedJvmSocketAddress(sender),
                        ecn = Ecn.Unknown,
                        localAddress = LocalAddress.Unknown,
                        hopLimit = HopLimit.Unknown,
                    ),
                )
            } catch (_: ClosedChannelException) {
                // close() races a receive: the channel (or its key on the loop) can be gone by the time
                // the receive reads or registers. The contract is that a receive on a closed channel
                // *yields* Closed, so this must not escape as an exception: a caller draining in a loop
                // would take a spurious failure purely from shutdown ordering.
                return DatagramReadResult.Closed()
            } finally {
                if (!handedOff) payload?.freeNativeMemory()
            }
        }
    }

    /**
     * Waits until the channel may be readable: in the loop's selector when this coroutine runs on a
     * [DatagramEventLoop], else in the selector of this channel's [home] loop. Returns when the channel
     * is closed too; the caller re-checks [closed].
     */
    private suspend fun awaitReadable() {
        val loop = currentCoroutineContext()[ContinuationInterceptor] as? DatagramEventLoop
        if (loop != null && loop.isLoopThread()) {
            // Registered BEFORE the closed check, and close() sets `closed` before it reads the set:
            // either this wait sees the close, or the close sees this loop and wakes the wait.
            loops.add(loop)
            if (closed) return
            loop.awaitReadable(channel)
            return
        }
        val home = home
        loops.add(home)
        if (closed) return
        withContext(home) { home.awaitReadable(channel) }
    }

    /** Shared send staging: closed-sink guard, control plane, and the zero-copy view to transmit. */
    protected fun stage(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ): ByteBuffer {
        check(!closed) { "sink is closed" }
        applyControlPlane(options)
        // Resolve to the raw buffer and take a java.nio slice over its readable window: the send
        // advances the slice's cursor, never the payload's (send-does-not-consume), and it is still a
        // view over the same memory (no copy). Deliberately NOT ReadBuffer.slice() — on a pooled
        // payload that returns a TrackedSlice holding a reference on the chunk, which this path has
        // nowhere to release, pinning one chunk out of the pool per send. Linux and Apple take
        // the same no-reference route via nativeAddress + position().
        return (payload.unwrapFully() as BaseJvmBuffer).byteBuffer.slice()
    }

    /**
     * Stage [payload] and transmit it with [write], which must return the NIO send/write result.
     *
     * The result is the point. This channel is non-blocking, and `DatagramChannel.write`/`send` are
     * documented to return "zero if there was insufficient room for the datagram in the underlying
     * output buffer" — so discarding the result turns local send-buffer pressure into an invisible
     * drop. That is not merely lossy: `QuicheDriver.flushOutgoing` counts a returned send as a
     * transmitted packet, so a dropped datagram inflates bytes-in-flight and is only discovered later
     * as spurious loss detection.
     *
     * A zero is therefore treated as backpressure, not as failure: wait briefly and retry the same
     * view (a datagram write is all-or-nothing, so a zero leaves the cursor untouched). Only when the
     * socket will not accept it within the send budget does it become a reported
     * [DatagramSendError.WouldBlock]. Surfacing backpressure any earlier would still be wrong — the
     * datagram genuinely has not been transmitted yet, and reporting a failure invites the caller to
     * retransmit something the kernel is about to send anyway. (`QuicheDriver.flushOutgoing` stops the
     * flush on a send failure and leaves termination to the idle timer, so this is a
     * quality-of-implementation concern rather than a correctness one.)
     *
     * The wait itself is [awaitWritable] — the socket's own `OP_WRITE` signal, not a timer.
     */
    protected suspend fun transmit(
        payload: ReadBuffer,
        options: DatagramSendOptions,
        write: (ByteBuffer) -> Int,
    ) {
        val view = stage(payload, options)
        val length = view.remaining()
        // Parity guard. NIO reports an oversized datagram as an IOException carrying no errno — only
        // its strerror phrase, which jvmSendErrorOf also recognizes — so this keeps the JVM reporting
        // TooLarge for the same condition every other backend does without a syscall to find out.
        if (length > maxWritableSize) {
            throw DatagramSendException(DatagramSendError.TooLarge(length, maxWritableSize))
        }
        // Off the Android main thread, where even a non-blocking `sendto` is a violation; anywhere else
        // (the QUIC datapath included) this sends in place.
        offTheAndroidMainThread { writeAbsorbingBackpressure(view, write, ::awaitWritable, limit = maxWritableSize) }
    }

    /**
     * [transmit]'s first write and nothing after it. [DatagramSendAttempt.WouldWait] when the socket has no
     * room, or the caller is the Android main thread (where even a non-blocking `sendto` is a violation):
     * nothing was sent and [payload] is untouched. A send that fails throws the typed
     * [DatagramSendException] [transmit] would.
     */
    protected fun tryTransmit(
        payload: ReadBuffer,
        options: DatagramSendOptions,
        write: (ByteBuffer) -> Int,
    ): DatagramSendAttempt {
        if (androidMainThread != null && Thread.currentThread() === androidMainThread) return DatagramSendAttempt.WouldWait
        val view = stage(payload, options)
        val length = view.remaining()
        if (length > maxWritableSize) {
            throw DatagramSendException(DatagramSendError.TooLarge(length, maxWritableSize))
        }
        val written =
            try {
                write(view)
            } catch (e: IOException) {
                throw DatagramSendException(jvmSendErrorOf(e, attempted = length, limit = maxWritableSize))
            }
        return if (written > 0) DatagramSendAttempt.Sent else DatagramSendAttempt.WouldWait
    }

    /**
     * Park until the kernel says this socket can accept a datagram again, or until [timeout] elapses.
     *
     * The reactive half of the send path: a full output buffer is a readiness condition the
     * platform already reports, so it is awaited rather than polled — one wakeup when the buffer
     * drains, instead of a backoff ladder of timer wakeups that both wastes them and adds latency to
     * the retry. The wait is `OP_WRITE` interest in a loop's selector (the caller's loop, else this
     * channel's [home] loop), so it returns as soon as there is room, and cancelling it leaves the
     * socket open. [timeout] is a timer on that wait, never a `select(0)`, which would mean forever.
     */
    internal suspend fun awaitWritable(timeout: Duration): WriteReadiness {
        if (closed) return WriteReadiness.Closed
        val current = currentCoroutineContext()[ContinuationInterceptor] as? DatagramEventLoop
        val loop = if (current != null && current.isLoopThread()) current else home
        // Registered BEFORE the closed check; close() sets `closed` before it reads the set.
        loops.add(loop)
        if (closed) return WriteReadiness.Closed
        return try {
            val ready =
                withTimeoutOrNull(timeout) {
                    if (loop === current) loop.awaitWritable(channel) else withContext(loop) { loop.awaitWritable(channel) }
                }
            when {
                closed -> WriteReadiness.Closed
                ready != null -> WriteReadiness.Writable
                else -> WriteReadiness.TimedOut
            }
        } catch (_: ClosedChannelException) {
            WriteReadiness.Closed
        }
    }

    override fun close() {
        closed = true
        // Every loop this channel waited on wakes its waiters before the key is cancelled: a cancelled
        // key is never selected, so a waiter the loop was not told about would wait forever.
        for (loop in loops) loop.channelClosing(channel)
        runCatching { channel.close() }
    }
}

/**
 * The **connected** (single fixed [peer]) mode of the NIO channel — what [UdpSocket.connect] returns.
 * `send` takes no destination; the kernel routes every datagram to the peer fixed at `connect()` time.
 */
@ExperimentalDatagramApi
internal class ConnectedNioDatagramChannel(
    channel: NioChannel,
    override val peer: SocketAddress,
    override val localAddress: LocalAddress,
    receiveBufferSize: Int = MAX_UDP_PAYLOAD,
    bufferFactory: BufferFactory = BufferFactory.Default,
) : NioDatagramChannelCore(channel, receiveBufferSize, bufferFactory),
    ConnectedDatagramChannel,
    NonSuspendingConnectedSend {
    override fun trySendNow(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ): DatagramSendAttempt = tryTransmit(payload, options) { channel.write(it) }

    override suspend fun send(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ) {
        // connected: the kernel routes to the fixed peer, so the write needs no destination
        transmit(payload, options) { channel.write(it) }
    }
}

/**
 * The **addressed** (many peers) mode of the NIO channel — what [UdpSocket.bind] returns. Every send
 * names its destination; [localAddress] is plainly non-null (bind fails fast on getsockname failure).
 */
@ExperimentalDatagramApi
internal class AddressedNioDatagramChannel(
    channel: NioChannel,
    override val localAddress: SocketAddress,
    receiveBufferSize: Int = MAX_UDP_PAYLOAD,
    bufferFactory: BufferFactory = BufferFactory.Default,
) : NioDatagramChannelCore(channel, receiveBufferSize, bufferFactory),
    AddressedDatagramChannel,
    NonSuspendingAddressedSend {
    override fun trySendNow(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions,
    ): DatagramSendAttempt {
        val dest = to.toInetSocketAddress()
        return tryTransmit(payload, options) { channel.send(it, dest) }
    }

    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions,
    ) {
        val dest = to.toInetSocketAddress()
        transmit(payload, options) { channel.send(it, dest) }
    }
}
