package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ByteSinkStalledException
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.OutboundQueueFullException
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketWriteStalledException
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * A typed message connection over a [ByteStream], framed by a [Codec].
 *
 * ## The connection owns its writer (#382)
 *
 * [send] does not touch the socket. It hands the message to a bounded outbound queue drained by a
 * single writer coroutine that this connection owns, which buys three properties that used to depend
 * on callers being careful and were silently absent:
 *
 * > A frame reaches the wire whole or not at all; concurrent sends to one connection cannot
 * > interleave; and no caller ever waits on the peer's socket.
 *
 * Previously `send` encoded and then called `stream.write` **on the caller's own coroutine**. That
 * gave the API three failure modes, none visible in its signature: two concurrent sends interleaved
 * their bytes under a length-prefix header (measured: `0x00 0x3c AAAA 0x00 0x3c BBBB AAAAAA BBBBBB…`);
 * cancelling a caller mid-write left a truncated frame whose header still promised the full length, so
 * the peer read on into the *next* frame and went quietly deaf; and one slow peer blocked the sending
 * coroutine and anything joined to it.
 *
 * The fan-out case is where all three land at once. `coroutineScope { for (c in conns) launch { c.send(f) } }`
 * cancels its siblings the instant one recipient fails — for any sibling inside `write`, mid-packet —
 * leaving every *healthy* peer holding a truncated frame, all going deaf at the same moment and
 * redialing in lockstep when their silence watchdogs fire.
 *
 * With the writer owned here, the only thing that can cancel a write is this connection's own
 * teardown, and a truncated frame on a stream being torn down harms nobody: truncation only matters if
 * the connection survives it.
 *
 * This is strictly stronger than [com.ditchoom.buffer.flow.Connection]'s documented contract, which
 * says implementations are not assumed to be thread-safe and concurrent sends need external
 * synchronisation. Callers of *this* implementation need none, and per-send time bounds added
 * defensively against the old behaviour can be deleted — with a hand-off, slowness becomes queue
 * depth, which can be generous precisely because nobody is waiting on it.
 *
 * @param scope the writer's lifetime. Required, and deliberately not defaulted: the writer lives as
 *   long as the connection, so its parent must be a scope the caller controls rather than whichever
 *   coroutine happened to call [send] first.
 * @param outboundCapacity how many messages may be queued before [overflowPolicy] applies. Required,
 *   and deliberately not defaulted — an inherited capacity is an inherited decision.
 * @param overflowPolicy what happens when the queue is full. Required for the same reason; see
 *   [OverflowPolicy].
 */
class CodecConnection<T>(
    val stream: ByteStream,
    val codec: Codec<T>,
    scope: CoroutineScope,
    private val outboundCapacity: Int,
    private val overflowPolicy: OverflowPolicy<T>,
    private val config: TransportConfig = TransportConfig(),
    private val decodeContext: DecodeContext = DecodeContext.Empty,
    private val encodeContext: EncodeContext = EncodeContext.Empty,
    override val id: Long = 0L,
) : com.ditchoom.buffer.flow.Connection<T> {
    /**
     * Source-compatible constructor for callers written against the pre-#382 signature.
     *
     * Deprecated rather than removed so that the fix reaches existing consumers without a migration:
     * code that compiled before this change still compiles, and immediately stops being able to
     * interleave or truncate frames. What it does **not** get is a say in the two decisions the primary
     * constructor exists to force.
     *
     * The defaults it fills in are the conservative ones:
     * - [OverflowPolicy.Suspend], the only policy that never discards a message, and the closest
     *   analogue to the old behaviour where a caller waited rather than shedding.
     * - [DEFAULT_OUTBOUND_CAPACITY] messages of queue depth.
     * - A writer scope this connection creates and owns. [close] cancels and joins the *writer*, so no
     *   coroutine keeps running — but the `SupervisorJob` behind that scope is never itself completed,
     *   which is one more reason the primary constructor's caller-supplied scope is the better shape.
     *
     * That last one is the reason to migrate. A self-owned scope is outside the caller's structured
     * concurrency, so cancelling the scope that created this connection does **not** stop its writer —
     * only [close] does. Passing a scope to the primary constructor ties the writer's lifetime to
     * something the caller controls, which is what "the connection owns its writer" is supposed to mean.
     *
     * Note what is *not* offered here: a way to keep writing on the caller's own coroutine. That
     * behaviour is the defect (#382), not a compatibility mode.
     */
    @Deprecated(
        message =
            "State the outbound queue policy explicitly: send() is now a hand-off to a writer this " +
                "connection owns, so a full queue is a decision only the caller can make. This " +
                "overload picks Suspend with a default capacity and a writer scope outside your " +
                "structured concurrency (only close() stops it). See #382.",
        replaceWith =
            ReplaceWith(
                "CodecConnection(stream, codec, scope, outboundCapacity, OverflowPolicy.Suspend, " +
                    "config, decodeContext, encodeContext, id)",
            ),
    )
    constructor(
        stream: ByteStream,
        codec: Codec<T>,
        config: TransportConfig = TransportConfig(),
        decodeContext: DecodeContext = DecodeContext.Empty,
        encodeContext: EncodeContext = EncodeContext.Empty,
        id: Long = 0L,
    ) : this(
        stream = stream,
        codec = codec,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        outboundCapacity = DEFAULT_OUTBOUND_CAPACITY,
        overflowPolicy = OverflowPolicy.Suspend,
        config = config,
        decodeContext = decodeContext,
        encodeContext = encodeContext,
        id = id,
    )

    init {
        require(outboundCapacity > 0) {
            "outboundCapacity must be positive, was $outboundCapacity — a zero-capacity queue would " +
                "make every send an overflow"
        }
    }

    /**
     * The hand-off. Its overflow behaviour is half of [overflowPolicy]; [send] implements the other
     * half, because the two drop policies cannot both be expressed by the channel.
     *
     * [OverflowPolicy.DropOldest] maps onto `BufferOverflow.DROP_OLDEST`, whose `onUndeliveredElement`
     * **does** receive the evicted message — verified, because the symmetric-looking `DROP_LATEST`
     * does **not**: a channel built that way silently drops the newest element and never calls the
     * handler, so a `DropNewest` implemented the obvious way would have had a callback that could
     * never fire. [OverflowPolicy.DropNewest] is therefore implemented in [send] with `trySend`.
     *
     * `onUndeliveredElement` also fires for anything still queued when the connection is closed
     * (see [close]), which is the same statement to the caller: this message will not reach the wire.
     */
    private val outbound: Channel<T> =
        when (overflowPolicy) {
            is OverflowPolicy.DropOldest ->
                Channel(
                    capacity = outboundCapacity,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                    onUndeliveredElement = overflowPolicy.onOverflow,
                )
            is OverflowPolicy.DropNewest ->
                Channel(
                    capacity = outboundCapacity,
                    onUndeliveredElement = overflowPolicy.onOverflow,
                )
            OverflowPolicy.Suspend, OverflowPolicy.Fail -> Channel(capacity = outboundCapacity)
        }

    /**
     * The single writer. Encoding happens here rather than at the send site so buffer lifetime and pool
     * discipline stay entirely inside this connection.
     *
     * A write failure is not rethrown. This is a plain `launch` in the caller's [scope], so throwing
     * would cancel that scope — a peer's broken pipe must not take down whatever else the caller is
     * running. The failure closes [outbound] with itself as the cause instead, so it surfaces at the
     * next [send] (and at [close]) as the original exception, which is where a caller can act on it.
     */
    private val writerJob: Job =
        scope.launch {
            try {
                for (message in outbound) {
                    encodeAndWriteFully(message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                outbound.close(t)
            } finally {
                // However this writer ended — failure, or [scope] being cancelled out from under it —
                // nothing can reach the wire any more, so the queue must stop accepting. Without this,
                // a cancelled scope left a connection that looked alive: `send` queued "successfully"
                // into a queue nobody drains, then suspended forever once it filled (Suspend), or
                // cycled messages into onOverflow for ever (DropOldest), or blamed the peer (Fail).
                // Writer *failure* already closed the channel; writer *cancellation* did not, and that
                // asymmetry was the bug.
                //
                // Both calls are no-ops once the channel is already closed and drained, which is the
                // normal [close] path — so this only bites the abnormal one. `cancel` hands anything
                // still queued to the overflow handler, which is the same statement as an overflow:
                // this message will not reach the wire.
                outbound.close(
                    SocketClosedException.General(
                        "the connection's writer stopped before this message could be written",
                    ),
                )
                outbound.cancel()
            }
        }

    /**
     * MultiThreaded, not the default SingleThreaded, because #382 made this pool genuinely shared: the
     * writer allocates and frees encode buffers on [scope]'s dispatcher, `receive()` acquires on
     * whichever thread collects it, and [close] clears it from a third. A SingleThreaded pool is
     * documented as "faster but NOT thread-safe" — plain ArrayDeque buckets and non-atomic refcounts —
     * and using one this way corrupts its structure (`ArrayIndexOutOfBoundsException` out of
     * `popAtLeast`) or livelocks on a double-handed-out buffer.
     *
     * Before #382 this was latent and avoidable: `send` ran on the caller's coroutine, so a
     * single-threaded consumer never crossed a thread. Now the writer is on another thread always, and
     * this class advertises that callers need no external synchronisation — so the pool has to mean it.
     * Every other pool in this repository that is shared this way already says MultiThreaded
     * ([com.ditchoom.socket.ReadBufferSource], QuicheDriver's stream/recv pools).
     */
    private val bufferPool: BufferPool =
        BufferPool(
            threadingMode = ThreadingMode.MultiThreaded,
            factory = config.bufferFactory,
        )
    private val streamProcessor: StreamProcessor = StreamProcessor.create(bufferPool)

    /**
     * A **fence**, not a latch.
     *
     * Its only job is the fast fail in [send], [preSeed] and [receive] — "this connection is closed,
     * your call is a mistake" — and `@Volatile` is the right tool for that: publication is all a
     * fence needs, and the tested contract is an [IllegalStateException] from those entry points.
     *
     * What it is deliberately **not** is the thing that decides who runs teardown. It used to be
     * both, and the conflation was the defect: `if (closed) return; closed = true` is check-then-act,
     * so concurrent closers all ran the whole teardown — measured at 223/300 attempts, worst case all
     * eight closers, each calling `streamProcessor.release()` on a deque that is not thread-safe.
     * Teardown is now latched by [teardownStarted]; this flag only fences.
     */
    @Volatile
    private var closed = false

    /**
     * Mutual exclusion over [streamProcessor], which no two coroutines may touch at once.
     *
     * This replaces a `@Volatile var receiving` guarded by `check(!receiving); receiving = true` —
     * check-then-act again, and measured admitting two collectors simultaneously into the flow in
     * 140/300 attempts. `receiving` was never a lifecycle state; it was a mutual-exclusion latch
     * written by hand, so it is now a [Mutex] and says so. `tryLock` keeps the loud, deterministic
     * rejection (silently letting two collectors split a framed stream would be a downgrade, not a
     * simplification), and unlocking in a `finally` preserves the documented sequential-collection
     * contract — handshake, then streaming.
     *
     * [close] takes the same lock before releasing the processor, which is what fixes the wider bug:
     * teardown racing a *live collector* threw `ConcurrentModificationException` out of `close()` in
     * 73/300 attempts with only ONE closer, because the collector's `streamProcessor.append` mutates
     * the very deque `release()` is iterating.
     */
    private val collecting = Mutex()

    /**
     * Teardown's once-only latch and its completion signal.
     *
     * `CompletableDeferred.complete()` returns true for exactly one concurrent caller — measured, not
     * assumed — so the winner runs teardown and every other caller awaits [teardownFinished] rather
     * than returning early. Returning early was itself a defect: the loser's `close()` could return
     * while the stream was still open, for up to the drain budget.
     *
     * Deliberately **not** the `outbound` channel's own `close()` return value, tempting though it is
     * (this class already calls it and discards it). The writer closes `outbound` from its own
     * `finally` on failure and on scope cancellation, so on exactly the paths where cleanup matters
     * most a real `close()` caller would be told it lost a race it never entered, and would skip
     * teardown entirely — trading a survivable double-run for a deterministic FD and native-memory
     * leak. The latch has to be one only [close] touches.
     */
    private val teardownStarted = CompletableDeferred<Unit>()
    private val teardownFinished = CompletableDeferred<Unit>()

    /** Latches the one release of [streamProcessor] and [bufferPool]; see [releaseResourcesOnce]. */
    private val resourcesReleased = CompletableDeferred<Unit>()

    private val _lastDataReceived = MutableStateFlow<TimeSource.Monotonic.ValueTimeMark?>(null)

    /** Timestamp of the most recent raw data read from the transport, or `null` if none yet. */
    val lastDataReceived: StateFlow<TimeSource.Monotonic.ValueTimeMark?> = _lastDataReceived.asStateFlow()

    /**
     * Pre-seeds the stream processor with leftover bytes from a prior protocol phase.
     *
     * Use this after a protocol upgrade (e.g., HTTP handshake → WebSocket framing)
     * where the handshake parser may have over-read into the next protocol's data.
     * Must be called before [receive].
     */
    fun preSeed(buffer: ReadBuffer) {
        check(!closed) { "CodecConnection is closed" }
        // tryLock rather than reading a flag: this both rejects a concurrent collector deterministically
        // and holds the processor for the duration of the append, so the two cannot mutate it at once.
        check(collecting.tryLock()) { "preSeed() cannot be called while receive() is being collected" }
        try {
            streamProcessor.append(buffer)
        } finally {
            collecting.unlock()
        }
    }

    /**
     * Returns a flow of decoded messages from the transport.
     *
     * Sequential collection is allowed (e.g., handshake then streaming),
     * but concurrent collection throws — two collectors would corrupt the stream processor.
     */
    override fun receive(): Flow<T> {
        check(!closed) { "CodecConnection is closed" }
        return flow {
            // Re-checked at COLLECTION time, not just at flow creation. `receive()` returns a cold
            // flow, so `val f = receive(); close(); f.collect { }` is a legal ordering that used to
            // walk straight into a released processor and a cleared pool with no guard at all.
            check(!closed) { "CodecConnection is closed" }
            check(collecting.tryLock()) { "receive() is already being collected concurrently" }
            try {
                emitDrainedFrames()
                while (fillFromTransport()) {
                    emitDrainedFrames()
                }
            } finally {
                // Whoever leaves last frees the processor. If close() ran while this collector held
                // the lock it will have skipped the release rather than corrupt a live deque, so the
                // obligation lands here — while the lock is still held, so nothing else can be inside.
                if (closed) releaseResourcesOnce()
                collecting.unlock()
            }
        }
    }

    private suspend fun FlowCollector<T>.emitDrainedFrames() {
        generateSequence { drainFrame() }.forEach { emit(it) }
    }

    /**
     * Hands [message] to this connection's writer. Does not touch the socket.
     *
     * Atomic and serialized by construction: the message is queued whole, and one writer drains the
     * queue, so nothing this caller does — including being cancelled the instant after this returns —
     * can truncate a frame or interleave it with another sender's.
     *
     * Cancellation is now honest. Before the hand-off, cancelling a caller mid-`write` corrupted the
     * stream for *every* subsequent frame; now it can only mean the message was or was not queued.
     *
     * Throws whatever the writer failed with if a previous write failed, since [outbound] is closed
     * with that cause — a queued-but-unwritten message is equivalent to one lost by the network, which
     * at-least-once layers already handle.
     */
    override suspend fun send(message: T) {
        check(!closed) { "CodecConnection is closed" }
        when (val policy = overflowPolicy) {
            // DROP_OLDEST never rejects and never suspends: the channel evicts to make room and hands
            // the evicted message to onOverflow itself.
            is OverflowPolicy.DropOldest -> suspendingSend(message)
            OverflowPolicy.Suspend -> suspendingSend(message)
            is OverflowPolicy.DropNewest -> {
                val result = outbound.trySend(message)
                if (!result.isSuccess) {
                    throwIfClosed(result)
                    policy.onOverflow(message)
                }
            }
            OverflowPolicy.Fail -> {
                val result = outbound.trySend(message)
                if (!result.isSuccess) {
                    throwIfClosed(result)
                    throw OutboundQueueFullException(outboundCapacity)
                }
            }
        }
    }

    /**
     * `outbound.send`, with the channel's own closed-exception re-homed into this library's error
     * family.
     *
     * A caller parked in `send` when the connection closes is resumed by kotlinx with a raw
     * `ClosedSendChannelException`, which is neither a [com.ditchoom.socket.SocketException] nor
     * something this API's header promises. The `trySend` paths already translate via [throwIfClosed];
     * these two did not.
     */
    private suspend fun suspendingSend(message: T) {
        try {
            outbound.send(message)
        } catch (e: ClosedSendChannelException) {
            throw SocketClosedException.General("CodecConnection is closed; the message was not queued", e)
        }
    }

    /**
     * Distinguishes the two reasons a `trySend` can fail: the queue is full (the policy's business) or
     * the writer already failed and closed the channel (the caller's). Only the latter throws here.
     */
    private fun throwIfClosed(result: ChannelResult<Unit>) {
        if (!result.isClosed) return
        throw result.exceptionOrNull()
            ?: SocketClosedException.General("CodecConnection's writer has stopped; the connection is closed")
    }

    /** Encode on the writer, then write the frame in full. Runs only on [writerJob]. */
    private suspend fun encodeAndWriteFully(message: T) {
        // Initial capacity: codec's per-call wireSize. Exact lets us allocate-exact
        // first try; BackPatch falls back to defaultBufferSize and may still overflow
        // for variable-length encodes (e.g. MQTT PUBLISH with a large payload). On
        // overflow, grow 4× and retry. Encode is expected to be deterministic, so
        // re-encoding into a larger buffer produces the same bytes. Retries are
        // bounded to avoid pathological loops.
        var capacity =
            when (val ws = codec.wireSize(message, encodeContext)) {
                is WireSize.Exact -> ws.bytes
                WireSize.BackPatch -> config.io.defaultBufferSize
            }
        var attempts = 0
        while (true) {
            val buffer = bufferPool.allocate(capacity)
            try {
                codec.encode(buffer, message, encodeContext)
                buffer.resetForRead()
                // writeFully, never a bare write: a sink may accept only PART of the buffer, and for a
                // self-framing codec a dropped tail is corruption rather than loss — the peer reads on
                // to the declared length and swallows the frames that follow. The no-arg overload keeps
                // the adapter rule (propagate, don't clobber): the leaf's writePolicy owns the deadline.
                stream.writeFully(buffer)
                return
            } catch (e: BufferOverflowException) {
                buffer.freeIfNeeded()
                if (attempts++ >= MAX_SEND_RESIZE_ATTEMPTS) throw e
                capacity = (capacity.toLong() * 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                continue
            } catch (e: ByteSinkStalledException) {
                // Re-home into this library's error family: buffer raises an IllegalStateException, and
                // every other failure `send` can produce is a SocketException — an IOException on JVM,
                // which is the convention SocketException's header promises consumers.
                buffer.freeIfNeeded()
                throw SocketWriteStalledException(e)
            } catch (t: Throwable) {
                buffer.freeIfNeeded()
                throw t
            }
        }
    }

    private fun drainFrame(): T? {
        val frameSize =
            when (val result = codec.peekFrameSize(streamProcessor, 0)) {
                is PeekResult.Complete -> result.bytes
                PeekResult.NeedsMoreData -> return null
                PeekResult.NoFraming ->
                    error(
                        "Codec ${codec::class.simpleName} reports NoFraming — " +
                            "cannot drive a streaming receive loop. Use a codec that " +
                            "implements peekFrameSize.",
                    )
            }
        if (streamProcessor.available() < frameSize) return null
        return streamProcessor.readBufferScoped(frameSize) { codec.decode(this, decodeContext) }
    }

    private suspend fun fillFromTransport(): Boolean =
        // Adapter rule: propagate, don't clobber. Call the leaf's no-arg read() so its injected
        // readPolicy governs the deadline. A WebTransport stream's UntilClosed survives; an HTTP/3
        // request stream's Bounded survives. Injecting a deadline here was the v5 footgun.
        when (val result = stream.read()) {
            is ReadResult.Data -> {
                _lastDataReceived.value = TimeSource.Monotonic.markNow()
                streamProcessor.append(result.buffer)
                true
            }
            is ReadResult.End -> false
            is ReadResult.Reset ->
                throw SocketClosedException.ConnectionReset("Stream reset by peer")
        }

    /**
     * Stops accepting sends, gives the writer a bounded chance to flush what is already queued, then
     * closes the stream.
     *
     * Drain-before-close, because once [send] is a hand-off `close()` would otherwise race ahead of a
     * just-queued goodbye frame — the common send-DISCONNECT-then-close shape would start silently
     * dropping it, with nothing at the call site to show for it.
     *
     * Bounded, because a peer that has stopped reading must not make `close()` hang. Waiting forever
     * would just move the stall from `send` to `close`, which is the failure mode the whole hand-off
     * exists to remove. Anything still queued when the budget expires is handed to the overflow
     * handler under the drop policies — the same statement as an overflow: this message will not
     * reach the wire.
     */
    override suspend fun close() {
        // The winner runs teardown; everyone else waits for it to finish rather than returning early
        // and reporting a closed connection whose stream is still open.
        if (!teardownStarted.complete(Unit)) {
            teardownFinished.await()
            return
        }
        closed = true
        try {
            // NonCancellable because the canonical call site is `finally { close() }`, and the usual
            // reason control reached that finally is that the caller was cancelled. Without this the
            // first suspension point below throws CancellationException and every step after it is
            // skipped — the transport never closed, the pool never cleared. Measured: the transport
            // was still open after a cancelled caller's close(). TypedMuxView wraps its close in
            // runCatching, so that leak is entirely silent. FallbackTransport already closes streams
            // this way for the same reason.
            withContext(NonCancellable) { runTeardown() }
        } finally {
            teardownFinished.complete(Unit)
        }
    }

    private suspend fun runTeardown() {
        outbound.close()
        withTimeoutOrNull(config.io.outboundDrainOnClose) { writerJob.join() }
        writerJob.cancel()
        // JOIN, not just cancel. The writer can be inside encodeAndWriteFully with a pooled buffer it
        // will hand back in its own finally; clearing the pool before that lands re-pools into a
        // cleared pool and leaks the buffer's native memory for the process's life. QuicheDriver
        // documents this same hazard ("BufferPool has no closed state ... the leaf allocation would
        // never be freed") and fixes it the same way, by joining before cleanup.
        writerJob.join()
        // Hands anything still queued to onUndeliveredElement; a no-op once the drain completed.
        outbound.cancel()
        // Before taking the collector lock: closing the transport is what unblocks a collector parked
        // in stream.read(), so it can leave the flow and hand the lock over.
        stream.close()
        // Never release the processor while a collector is inside it. If the lock cannot be taken
        // within the drain budget the release is deliberately skipped rather than forced — the
        // collector's own finally performs it on the way out, because `closed` is already true.
        if (withTimeoutOrNull(config.io.outboundDrainOnClose) { collecting.lock() } != null) {
            try {
                releaseResourcesOnce()
            } finally {
                collecting.unlock()
            }
        }
    }

    /**
     * Frees the processor and the encode pool exactly once, whichever of [close] or a departing
     * collector gets there first. Both call it holding [collecting], so it never runs concurrently
     * with an `append` or a `readBufferScoped`.
     */
    private fun releaseResourcesOnce() {
        if (!resourcesReleased.complete(Unit)) return
        streamProcessor.release()
        bufferPool.clear()
    }

    companion object {
        /**
         * Queue depth the deprecated pre-#382 constructor fills in.
         *
         * Deep enough that an ordinary sender never reaches it, so the deprecated overload behaves like
         * the unbounded-feeling old API in practice, and shallow enough that a peer which has genuinely
         * stopped draining applies back-pressure rather than growing without limit. Callers who care
         * should state their own — which is what the primary constructor is for.
         */
        const val DEFAULT_OUTBOUND_CAPACITY = 64

        private const val MAX_SEND_RESIZE_ATTEMPTS = 20

        /**
         * Connects and wraps the stream in a [CodecConnection].
         *
         * [scope], [outboundCapacity] and [overflowPolicy] are threaded through rather than defaulted
         * for the reason the constructor states: the writer's lifetime has to be tied to a scope the
         * caller controls, not to whichever coroutine happens to call [send] first.
         */
        suspend fun <T> connect(
            hostname: String,
            port: Int,
            codec: Codec<T>,
            scope: CoroutineScope,
            outboundCapacity: Int = DEFAULT_OUTBOUND_CAPACITY,
            overflowPolicy: OverflowPolicy<T> = OverflowPolicy.Suspend,
            transport: Transport = TcpTransport(),
            config: TransportConfig = TransportConfig(),
            decodeContext: DecodeContext = DecodeContext.Empty,
            encodeContext: EncodeContext = EncodeContext.Empty,
        ): CodecConnection<T> {
            val stream = transport.connect(hostname, port, config)
            return CodecConnection(
                stream = stream,
                codec = codec,
                scope = scope,
                outboundCapacity = outboundCapacity,
                overflowPolicy = overflowPolicy,
                config = config,
                decodeContext = decodeContext,
                encodeContext = encodeContext,
            )
        }
    }
}
