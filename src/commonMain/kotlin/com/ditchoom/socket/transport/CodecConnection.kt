package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.buffer.ExperimentalFanoutApi
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.ContextFreeCodec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.SharedFrame
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ByteSinkStalledException
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ConnectionPhase
import com.ditchoom.buffer.flow.OutboundClosedException
import com.ditchoom.buffer.flow.OutboundWriter
import com.ditchoom.buffer.flow.Outgoing
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.SendMode
import com.ditchoom.buffer.flow.TransmitOutcome
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketWriteStalledException
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * A typed [com.ditchoom.buffer.flow.Connection] over a [ByteStream], framed by a [Codec].
 *
 * ## Send contract
 *
 * This connection **owns its writer** ([OutboundWriter]), which is what makes it a *conforming*
 * implementation of `Connection`'s revised send contract:
 *
 * 1. **Atomic** — a message reaches the wire whole or not at all. Callers hand the message off; the
 *    write runs on the connection's own coroutine, so cancelling a sender can no longer leave a
 *    partial frame under a length prefix that declares the whole thing (issue #382).
 * 2. **Serialized** — concurrent `send`s on one connection cannot interleave their bytes. No caller
 *    needs an external `Mutex`; there is nothing left for one to protect.
 *
 * The default [SendMode.AwaitWritten] keeps the observable semantics of a direct write: `send`
 * returns once the frame is on the wire, write errors throw at the call site, and send-then-close
 * needs no drain contract. One edge follows from the ownership and is documented rather than hidden:
 * **cancelled ≠ not sent**. A sender cancelled after the writer has taken its message unwinds with
 * `CancellationException` while the writer finishes the frame — the frame completes whole and cannot
 * be un-sent. The sender abandons the *wait*, never the *write*.
 *
 * [SendMode.Handoff] is the opt-in fan-out mode: `send` returns on enqueue, no sender ever waits on a
 * peer's socket, and every accepted message that will not reach the wire is reported exactly once
 * through `onNotSent`. A producer looping over many connections wants that; a request/response client
 * wants the default.
 *
 * ## Lifecycle
 *
 * The writer coroutine lives from construction to [close]/[abort] — a connection that is built and
 * then abandoned leaks it, so close the connection even if nothing was ever sent.
 *
 * [close] is graceful: the writer drains per its mode, *then* the stream is closed, so a frame handed
 * off just before the call still reaches the peer. That ordering has a cost worth stating plainly —
 * a graceful close waits for the in-flight frame, and each write is bounded only by the stream's own
 * [com.ditchoom.buffer.flow.WritePolicy], which defaults to `UntilClosed`. Against a peer that has
 * stopped reading entirely, `close()` therefore waits with it. [abort] is the escape and the RST-like
 * counterpart: the writer is cancelled wherever it is — truncating a frame on a connection being torn
 * down harms nobody, which is exactly what makes the ownership safe — and the transport is released
 * immediately. It is callable *while* a `close()` is draining.
 */
class CodecConnection<T>(
    val stream: ByteStream,
    val codec: Codec<T>,
    private val config: TransportConfig = TransportConfig(),
    private val decodeContext: DecodeContext = DecodeContext.Empty,
    private val encodeContext: EncodeContext = EncodeContext.Empty,
    override val id: Long = 0L,
    sendMode: SendMode<T> = SendMode.AwaitWritten,
) : com.ditchoom.buffer.flow.Connection<T> {
    /** Receive-side pool: feeds [streamProcessor], touched only by the collecting coroutine. */
    private val bufferPool: BufferPool = BufferPool(factory = config.bufferFactory)
    private val streamProcessor: StreamProcessor = StreamProcessor.create(bufferPool)

    /**
     * Send-side pool, separate from [bufferPool] on purpose. Both run in [BufferPool]'s default
     * single-threaded mode, which is only sound while a pool has ONE user — and the encode now runs on
     * the writer's coroutine rather than the caller's, so a shared pool would be allocated from by the
     * writer and by the receive collector at the same time. Two pools keep each genuinely
     * single-threaded instead of paying for atomics on the receive hot path.
     */
    private val sendPool: BufferPool = BufferPool(factory = config.bufferFactory)

    /**
     * `close()`/`abort()` was requested. Deliberately NOT derived from [sendPhase]: that phase is the
     * *send* ladder, and a writer that failed on a transport error reaches `Closed` without anybody
     * having asked this connection to shut down — deriving the receive-side guards from it would make a
     * write failure silently refuse `receive()`/`preSeed()`, which is different (and wrong) behaviour.
     * The send guard IS fully derived: [send] has no flag check at all, only the writer's phase
     * discipline, which is what closes the send-after-close TOCTOU.
     */
    @Volatile
    private var closed = false

    /** One-shot transport teardown, so `close()` after `abort()` — or either twice — releases once. */
    @Volatile
    private var released = false

    @Volatile
    private var receiving = false

    private val _lastDataReceived = MutableStateFlow<TimeSource.Monotonic.ValueTimeMark?>(null)

    /** Timestamp of the most recent raw data read from the transport, or `null` if none yet. */
    val lastDataReceived: StateFlow<TimeSource.Monotonic.ValueTimeMark?> = _lastDataReceived.asStateFlow()

    @OptIn(ExperimentalFanoutApi::class)
    private val writer = OutboundWriter(sendMode, ::transmit)

    /**
     * The send/close ladder, reactively.
     *
     * Named `sendPhase` and not `phase`: it describes when this connection stops accepting sends and
     * why it settled, which is a different question from the transport's establishment lifecycle
     * ([com.ditchoom.socket.ConnectionState]). The two vocabularies coexist by intent.
     */
    @OptIn(ExperimentalFanoutApi::class)
    val sendPhase: StateFlow<ConnectionPhase> get() = writer.phase

    /**
     * Pre-seeds the stream processor with leftover bytes from a prior protocol phase.
     *
     * Use this after a protocol upgrade (e.g., HTTP handshake → WebSocket framing)
     * where the handshake parser may have over-read into the next protocol's data.
     * Must be called before [receive].
     */
    fun preSeed(buffer: ReadBuffer) {
        check(!closed) { "CodecConnection is closed" }
        check(!receiving) { "preSeed() cannot be called while receive() is being collected" }
        streamProcessor.append(buffer)
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
            check(!receiving) { "receive() is already being collected concurrently" }
            receiving = true
            try {
                emitDrainedFrames()
                while (fillFromTransport()) {
                    emitDrainedFrames()
                }
            } finally {
                receiving = false
            }
        }
    }

    private suspend fun FlowCollector<T>.emitDrainedFrames() {
        generateSequence { drainFrame() }.forEach { emit(it) }
    }

    /**
     * Hands [message] to the connection-owned writer.
     *
     * There is no `closed` guard here by design — the writer decides, atomically with its own queue,
     * whether a send is still admissible, so send-after-close is a definite answer instead of a
     * check-then-race.
     */
    @OptIn(ExperimentalFanoutApi::class)
    override suspend fun send(message: T) {
        try {
            writer.send(message)
        } catch (e: OutboundClosedException) {
            throw SocketClosedException.OutboundClosed(e.closeCause, e)
        }
    }

    /**
     * Sends bytes that were encoded **once** for fan-out across many connections: build the frame with
     * [com.ditchoom.buffer.codec.encodeShared], send it over N connections, then `close()` the frame.
     * This call retains a reference for the duration of the send, and the writer releases it exactly
     * once on whichever path the element takes — written, refused, dropped, or lost at close.
     *
     * **The codec pairing is the caller's responsibility.** This connection cannot verify that [frame]
     * was encoded by *its* [codec]: the frame carries bytes and an origin message, not the codec that
     * produced them. Sending a frame built by a different codec puts bytes on the wire that the peer
     * will mis-frame, exactly as writing raw bytes would. `encodeShared` being scoped to
     * [ContextFreeCodec] makes *context*-dependent encodes (a QPACK dynamic table, per-connection ids)
     * unrepresentable here, but codec *identity* is not something the types carry.
     */
    @ExperimentalFanoutApi
    suspend fun send(frame: SharedFrame<T>) {
        try {
            writer.sendShared(frame.bytes.retain(), frame.origin)
        } catch (e: OutboundClosedException) {
            throw SocketClosedException.OutboundClosed(e.closeCause, e)
        }
    }

    /**
     * The writer's transmit stage — the old body of [send], moved onto the writer.
     *
     * Called serially, only from the writer coroutine. Three failure classes, each with its own exit:
     * an **encode** failure (a codec that throws, or a [BufferOverflowException] still overflowing
     * after [MAX_SEND_RESIZE_ATTEMPTS] growths) returns [TransmitOutcome.EncodeFailed] — per-message,
     * deterministic, no bytes written, connection survives; a **transport** failure *throws*, which
     * fails the writer and with it the connection; and a **cancellation** (an [abort] mid-frame)
     * propagates so the writer settles its own accounting.
     */
    @OptIn(ExperimentalFanoutApi::class)
    private suspend fun transmit(outgoing: Outgoing<T>): TransmitOutcome =
        when (outgoing) {
            is Outgoing.Encode -> encodeAndWrite(outgoing.message)
            is Outgoing.Prewritten -> {
                // Already-encoded shared bytes: the writer owns the reference, this stage only writes.
                writeFrame(outgoing.view)
                TransmitOutcome.Written
            }
        }

    @OptIn(ExperimentalFanoutApi::class)
    private suspend fun encodeAndWrite(message: T): TransmitOutcome {
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
            val buffer = sendPool.allocate(capacity)
            try {
                codec.encode(buffer, message, encodeContext)
                buffer.resetForRead()
            } catch (e: BufferOverflowException) {
                buffer.freeIfNeeded()
                if (attempts++ >= MAX_SEND_RESIZE_ATTEMPTS) return TransmitOutcome.EncodeFailed(e)
                capacity = (capacity.toLong() * 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                continue
            } catch (e: CancellationException) {
                buffer.freeIfNeeded()
                throw e
            } catch (t: Throwable) {
                // A codec that cannot encode this value is a per-message fact, not a dead connection:
                // report it and let the next frame through.
                buffer.freeIfNeeded()
                return TransmitOutcome.EncodeFailed(t)
            }
            try {
                writeFrame(buffer)
            } catch (t: Throwable) {
                // Same free discipline as before the move: every failure path returns the encode
                // buffer, and the success path leaves it as it was — who runs the write is a separate
                // question from when the pool gets its chunk back.
                buffer.freeIfNeeded()
                throw t
            }
            return TransmitOutcome.Written
        }
    }

    /**
     * writeFully, never a bare write: a sink may accept only PART of the buffer, and for a self-framing
     * codec a dropped tail is corruption rather than loss — the peer reads on to the declared length and
     * swallows the frames that follow. The no-arg overload keeps the adapter rule (propagate, don't
     * clobber): the leaf's writePolicy owns the deadline.
     */
    private suspend fun writeFrame(frame: ReadBuffer) {
        try {
            stream.writeFully(frame)
        } catch (e: ByteSinkStalledException) {
            // Re-home into this library's error family: buffer raises an IllegalStateException, and
            // every other failure `send` can produce is a SocketException — an IOException on JVM,
            // which is the convention SocketException's header promises consumers.
            throw SocketWriteStalledException(e)
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
     * Graceful close: the writer drains first (per its [SendMode]), then the transport is released.
     * Idempotent.
     */
    @OptIn(ExperimentalFanoutApi::class)
    override suspend fun close() {
        closed = true
        writer.close()
        releaseTransport()
    }

    /**
     * Immediate close: cancel the writer wherever it is and release the transport now. Queued frames
     * are reported not-sent through the mode's loss path. Idempotent — and callable *while* a [close]
     * is draining, which is how a graceful close of a stalled peer gets escalated.
     */
    @OptIn(ExperimentalFanoutApi::class)
    override suspend fun abort() {
        closed = true
        writer.abort()
        releaseTransport()
    }

    private suspend fun releaseTransport() {
        if (released) return
        released = true
        stream.close()
        streamProcessor.release()
        bufferPool.clear()
        sendPool.clear()
    }

    companion object {
        private const val MAX_SEND_RESIZE_ATTEMPTS = 20

        suspend fun <T> connect(
            hostname: String,
            port: Int,
            codec: Codec<T>,
            transport: Transport = TcpTransport(),
            config: TransportConfig = TransportConfig(),
            decodeContext: DecodeContext = DecodeContext.Empty,
            encodeContext: EncodeContext = EncodeContext.Empty,
            sendMode: SendMode<T> = SendMode.AwaitWritten,
        ): CodecConnection<T> {
            val stream = transport.connect(hostname, port, config)
            return CodecConnection(stream, codec, config, decodeContext, encodeContext, sendMode = sendMode)
        }
    }
}
