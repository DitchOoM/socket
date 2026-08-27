package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteSinkStalledException
import com.ditchoom.buffer.flow.Sender
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.socket.OutboundQueueFullException
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketWriteStalledException
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adapts a send-only [ByteSink] to a typed [Sender] using a [Codec] — the honest counterpart of
 * [CodecConnection] for a **unidirectional outbound** stream. Each [send] hands one message to a
 * writer this sender owns; there is no read side (it is a [ByteSink], not a
 * [com.ditchoom.buffer.flow.ByteStream]).
 *
 * [close] FINs the send side via [ByteSink.close], so the peer's [com.ditchoom.buffer.flow.Receiver]
 * flow completes. [id] mirrors the underlying QUIC stream id for cross-layer log correlation.
 *
 * ## The writer (#469)
 *
 * #382 gave [CodecConnection] a writer it owns; this class — the leaf behind
 * [TypedMuxView.openUnidirectional] — kept encoding and writing on the **caller's** coroutine, and so
 * kept all three of the defects #382 describes:
 *
 * - **Not serialized.** Two coroutines sending on one sender interleaved their bytes under a
 *   length-prefix header, producing a frame the peer cannot decode.
 * - **Not atomic.** Cancelling a caller mid-write left a truncated frame whose header still promised
 *   the full length, so the peer read on into whatever followed and went silently deaf.
 * - **Not async.** The caller waited on the peer's socket.
 *
 * Being unidirectional does not soften any of that: a self-framing codec is exactly where a spliced
 * or truncated frame is corruption rather than loss. What it does change is scope — there is no
 * `receive` side, so no stream processor and no collector to exclude, and teardown is correspondingly
 * shorter than [CodecConnection]'s.
 */
class CodecSender<T>(
    val sink: ByteSink,
    val codec: Codec<T>,
    scope: CoroutineScope,
    private val outboundCapacity: Int,
    private val overflowPolicy: OverflowPolicy<T>,
    private val config: TransportConfig = TransportConfig(),
    private val encodeContext: EncodeContext = EncodeContext.Empty,
    override val id: Long = 0L,
) : Sender<T> {
    /**
     * Source-compatible constructor for callers written against the pre-#469 signature.
     *
     * Same shape and same reasoning as [CodecConnection]'s deprecated overload: existing code keeps
     * compiling and immediately stops being able to interleave or truncate frames, while giving up any
     * say in the two decisions the primary constructor exists to force. The scope it fills in is one
     * this sender creates and owns, so it sits outside the caller's structured concurrency and only
     * [close] stops the writer — which is the reason to migrate.
     */
    @Deprecated(
        message =
            "State the outbound queue policy explicitly: send() is now a hand-off to a writer this " +
                "sender owns, so a full queue is a decision only the caller can make. This overload " +
                "picks Suspend with a default capacity and a writer scope outside your structured " +
                "concurrency (only close() stops it). See #469.",
        replaceWith =
            ReplaceWith(
                "CodecSender(sink, codec, scope, outboundCapacity, OverflowPolicy.Suspend, " +
                    "config, encodeContext, id)",
            ),
    )
    constructor(
        sink: ByteSink,
        codec: Codec<T>,
        config: TransportConfig = TransportConfig(),
        encodeContext: EncodeContext = EncodeContext.Empty,
        id: Long = 0L,
    ) : this(
        sink = sink,
        codec = codec,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        outboundCapacity = CodecConnection.DEFAULT_OUTBOUND_CAPACITY,
        overflowPolicy = OverflowPolicy.Suspend,
        config = config,
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
     * MultiThreaded for the reason [CodecConnection.bufferPool] documents, which #469 makes true here
     * too. Before the writer this pool was touched by one role on one coroutine; now the writer
     * allocates and frees encode buffers on [scope]'s dispatcher while [close] clears it from
     * whichever thread called it, so the "faster but NOT thread-safe" SingleThreaded mode would be
     * corrupting its own buckets.
     */
    private val bufferPool: BufferPool =
        BufferPool(
            threadingMode = ThreadingMode.MultiThreaded,
            factory = config.bufferFactory,
        )

    /**
     * The hand-off. Its overflow behaviour is half of [overflowPolicy] and [send] implements the
     * other half, for the reason [CodecConnection.outbound] documents: `DROP_LATEST` never calls
     * `onUndeliveredElement`, so [OverflowPolicy.DropNewest] has to be done with `trySend`.
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
     * The single writer. Encoding happens here rather than at the send site so buffer lifetime and
     * pool discipline stay entirely inside this sender.
     *
     * A write failure is not rethrown — this is a plain `launch` in the caller's [scope], and a
     * peer's broken pipe must not cancel whatever else that scope is running. It closes [outbound]
     * with itself as the cause instead, so it surfaces at the next [send] as the original exception.
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
                // However this writer ended — failure, or [scope] cancelled out from under it —
                // nothing can reach the wire any more, so the queue must stop accepting. Writer
                // *failure* already closed the channel; writer *cancellation* did not, and that
                // asymmetry is the same one #382 fixed in CodecConnection: a cancelled scope left a
                // sender that looked alive, queueing into a queue nobody drains.
                outbound.close(
                    SocketClosedException.General(
                        "the sender's writer stopped before this message could be written",
                    ),
                )
                outbound.cancel()
            }
        }

    /** Teardown's once-only latch; see [TeardownOnce]. */
    private val teardown = TeardownOnce()

    /** The fence [send] fast-fails on, read through the latch so the two cannot drift. */
    private val closed: Boolean get() = teardown.begun

    /**
     * Hands [message] to this sender's writer. Does not touch the sink.
     *
     * Atomic and serialized by construction: the message is queued whole and one writer drains the
     * queue, so nothing this caller does — including being cancelled the instant after this returns —
     * can truncate a frame or interleave it with another sender's.
     */
    override suspend fun send(message: T) {
        check(!closed) { "CodecSender is closed" }
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
     * family — a caller parked in `send` when the sender closes is otherwise resumed with a raw
     * `ClosedSendChannelException`, which this API's header does not promise.
     */
    private suspend fun suspendingSend(message: T) {
        try {
            outbound.send(message)
        } catch (e: ClosedSendChannelException) {
            throw SocketClosedException.General("CodecSender is closed; the message was not queued", e)
        }
    }

    /**
     * Distinguishes the two reasons a `trySend` can fail: the queue is full (the policy's business) or
     * the writer already failed and closed the channel (the caller's). Only the latter throws here.
     */
    private fun throwIfClosed(result: ChannelResult<Unit>) {
        if (!result.isClosed) return
        val cause = result.exceptionOrNull()
        throw cause
            ?: SocketClosedException.General("CodecSender is closed; the message was not queued")
    }

    override suspend fun close() = teardown.runOnce { runTeardown() }

    private suspend fun runTeardown() {
        outbound.close()
        withTimeoutOrNull(config.io.outboundDrainOnClose) { writerJob.join() }
        writerJob.cancel()
        // JOIN, not just cancel. The writer can be inside encodeAndWriteFully holding a pooled buffer
        // it hands back in its own finally; clearing the pool before that lands re-pools into a
        // cleared pool and leaks the buffer's native memory for the process's life.
        writerJob.join()
        // Hands anything still queued to onUndeliveredElement; a no-op once the drain completed.
        outbound.cancel()
        // Bounded, and inside NonCancellable: unbounded work there would be UNKILLABLE, and a
        // uni-stream close is a command round-trip to the QUIC driver loop.
        withTimeoutOrNull(config.io.outboundDrainOnClose) { sink.close() }
        // Outside the timeout on purpose: the pool must be cleared even when the sink close ran out of
        // budget, or a dead peer would leak this sender's encode buffers.
        bufferPool.clear()
    }

    private suspend fun encodeAndWriteFully(message: T) {
        // Same encode-then-resize strategy as CodecConnection.encodeAndWriteFully: start from the
        // codec's wireSize, grow 4× on overflow (encode is deterministic, so re-encoding into a larger
        // buffer is safe), bounded to avoid pathological loops.
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
                // to the declared length and swallows the frames that follow.
                // Adapter rule: propagate, don't clobber. The no-arg overload lets the leaf's injected
                // writePolicy govern the deadline — never inject our own.
                sink.writeFully(buffer)
                return
            } catch (e: BufferOverflowException) {
                buffer.freeIfNeeded()
                if (attempts++ >= MAX_SEND_RESIZE_ATTEMPTS) throw e
                capacity = (capacity.toLong() * 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                continue
            } catch (e: ByteSinkStalledException) {
                // Re-home into this library's error family, as CodecConnection does.
                buffer.freeIfNeeded()
                throw SocketWriteStalledException(e)
            } catch (t: Throwable) {
                buffer.freeIfNeeded()
                throw t
            }
        }
    }

    private companion object {
        private const val MAX_SEND_RESIZE_ATTEMPTS = 20
    }
}
