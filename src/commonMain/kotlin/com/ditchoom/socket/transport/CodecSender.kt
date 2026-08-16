package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.buffer.ExperimentalFanoutApi
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteSinkStalledException
import com.ditchoom.buffer.flow.ConnectionPhase
import com.ditchoom.buffer.flow.OutboundClosedException
import com.ditchoom.buffer.flow.OutboundWriter
import com.ditchoom.buffer.flow.Outgoing
import com.ditchoom.buffer.flow.SendMode
import com.ditchoom.buffer.flow.Sender
import com.ditchoom.buffer.flow.TransmitOutcome
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketWriteStalledException
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * Adapts a send-only [ByteSink] to a typed [Sender] using a [Codec] — the honest counterpart of
 * [CodecConnection] for a **unidirectional outbound** stream. Each [send] hands one message to this
 * sender's own writer, which encodes it and writes it; there is no read side (it is a [ByteSink], not
 * a [com.ditchoom.buffer.flow.ByteStream]).
 *
 * Like [CodecConnection], this **owns its writer** ([OutboundWriter]), so sends are atomic (a
 * cancelled caller cannot truncate a frame) and serialized (concurrent senders cannot interleave) —
 * the mux leaf carried a byte-for-byte copy of the caller-writes defect and gets the same fix.
 * [SendMode.AwaitWritten], the default, keeps `send`'s observable semantics: it returns once the frame
 * is on the wire and write errors throw at the call site.
 *
 * [close] drains the writer and then FINs the send side via [ByteSink.close], so the peer's
 * [com.ditchoom.buffer.flow.Receiver] flow completes. The writer coroutine lives from construction
 * until [close]/[abort], so an abandoned sender leaks it. [id] mirrors the underlying QUIC stream id
 * for cross-layer log correlation.
 */
class CodecSender<T>(
    val sink: ByteSink,
    val codec: Codec<T>,
    private val config: TransportConfig = TransportConfig(),
    private val encodeContext: EncodeContext = EncodeContext.Empty,
    override val id: Long = 0L,
    sendMode: SendMode<T> = SendMode.AwaitWritten,
) : Sender<T> {
    private val bufferPool: BufferPool = BufferPool(factory = config.bufferFactory)

    /** One-shot teardown, so a second [close] does not re-FIN the sink or re-clear the pool. */
    @Volatile
    private var released = false

    @OptIn(ExperimentalFanoutApi::class)
    private val writer = OutboundWriter(sendMode, ::transmit)

    /**
     * The send/close ladder, reactively. Named `sendPhase` for the same reason as
     * [CodecConnection.sendPhase]: it is the send ladder, not the transport's establishment lifecycle.
     */
    @OptIn(ExperimentalFanoutApi::class)
    val sendPhase: StateFlow<ConnectionPhase> get() = writer.phase

    /**
     * Hands [message] to this sender's writer. No `closed` flag guards it — the writer decides
     * admissibility atomically with its own queue.
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
     * The writer's transmit stage — the old body of [send], moved onto the writer. Encode failures are
     * returned as [TransmitOutcome.EncodeFailed] (per-message, no bytes written); transport failures
     * throw and fail the writer.
     */
    @OptIn(ExperimentalFanoutApi::class)
    private suspend fun transmit(outgoing: Outgoing<T>): TransmitOutcome =
        when (outgoing) {
            is Outgoing.Encode -> encodeAndWrite(outgoing.message)
            is Outgoing.Prewritten -> {
                writeFrame(outgoing.view)
                TransmitOutcome.Written
            }
        }

    @OptIn(ExperimentalFanoutApi::class)
    private suspend fun encodeAndWrite(message: T): TransmitOutcome {
        // Same encode-then-resize strategy as CodecConnection: start from the codec's wireSize, grow 4×
        // on overflow (encode is deterministic, so re-encoding into a larger buffer is safe).
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
            } catch (e: BufferOverflowException) {
                buffer.freeIfNeeded()
                if (attempts++ >= MAX_SEND_RESIZE_ATTEMPTS) return TransmitOutcome.EncodeFailed(e)
                capacity = (capacity.toLong() * 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                continue
            } catch (e: CancellationException) {
                buffer.freeIfNeeded()
                throw e
            } catch (t: Throwable) {
                buffer.freeIfNeeded()
                return TransmitOutcome.EncodeFailed(t)
            }
            try {
                writeFrame(buffer)
            } catch (t: Throwable) {
                buffer.freeIfNeeded()
                throw t
            }
            return TransmitOutcome.Written
        }
    }

    /**
     * writeFully, never a bare write: a sink may accept only PART of the buffer, and for a self-framing
     * codec a dropped tail is corruption rather than loss — the peer reads on to the declared length and
     * swallows the frames that follow. Adapter rule: propagate, don't clobber. The no-arg overload lets
     * the leaf's injected writePolicy govern the deadline — never inject our own.
     */
    private suspend fun writeFrame(frame: ReadBuffer) {
        try {
            sink.writeFully(frame)
        } catch (e: ByteSinkStalledException) {
            // Re-home into this library's error family, as CodecConnection does.
            throw SocketWriteStalledException(e)
        }
    }

    /**
     * Graceful close: drain the writer, then FIN the sink. Idempotent.
     *
     * The drain is bounded only by the sink's own [com.ditchoom.buffer.flow.WritePolicy], so against a
     * peer that has stopped reading this waits with it — [abort] is the escape.
     */
    @OptIn(ExperimentalFanoutApi::class)
    override suspend fun close() {
        writer.close()
        releaseSink()
    }

    /**
     * Immediate close: cancel the writer wherever it is, then FIN the sink. The unidirectional mirror
     * of [CodecConnection.abort] — without it a leaf whose peer stalls would have no way out of
     * [close]. Idempotent, and callable while a [close] is draining.
     */
    @OptIn(ExperimentalFanoutApi::class)
    suspend fun abort() {
        writer.abort()
        releaseSink()
    }

    private suspend fun releaseSink() {
        if (released) return
        released = true
        sink.close()
        bufferPool.clear()
    }

    private companion object {
        private const val MAX_SEND_RESIZE_ATTEMPTS = 20
    }
}
