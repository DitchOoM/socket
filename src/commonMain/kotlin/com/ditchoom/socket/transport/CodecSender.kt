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
import com.ditchoom.socket.SocketWriteStalledException
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/**
 * Adapts a send-only [ByteSink] to a typed [Sender] using a [Codec] — the honest counterpart of
 * [CodecConnection] for a **unidirectional outbound** stream. Each [send] encodes one message and
 * writes it; there is no read side (it is a [ByteSink], not a [com.ditchoom.buffer.flow.ByteStream]).
 *
 * [close] FINs the send side via [ByteSink.close], so the peer's [com.ditchoom.buffer.flow.Receiver]
 * flow completes. [id] mirrors the underlying QUIC stream id for cross-layer log correlation.
 */
class CodecSender<T>(
    val sink: ByteSink,
    val codec: Codec<T>,
    private val config: TransportConfig = TransportConfig(),
    private val encodeContext: EncodeContext = EncodeContext.Empty,
    override val id: Long = 0L,
) : Sender<T> {
    private val bufferPool: BufferPool = BufferPool(factory = config.bufferFactory)

    /**
     * A fence for [send], not the teardown latch — see [CodecConnection.closed] for why the two must
     * be different things.
     */
    @Volatile
    private var closed = false

    /** Teardown's once-only latch and its completion signal; see [CodecConnection.teardownStarted]. */
    private val teardownStarted = CompletableDeferred<Unit>()
    private val teardownFinished = CompletableDeferred<Unit>()

    override suspend fun send(message: T) {
        check(!closed) { "CodecSender is closed" }
        // Same encode-then-resize strategy as CodecConnection.send: start from the codec's wireSize,
        // grow 4× on overflow (encode is deterministic, so re-encoding into a larger buffer is safe).
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
                // Re-home into this library's error family, as CodecConnection.send does.
                buffer.freeIfNeeded()
                throw SocketWriteStalledException(e)
            } catch (t: Throwable) {
                buffer.freeIfNeeded()
                throw t
            }
        }
    }

    override suspend fun close() {
        // The winner tears down; everyone else waits for it rather than returning early and reporting
        // a closed sender whose sink is still open.
        if (!teardownStarted.complete(Unit)) {
            teardownFinished.await()
            return
        }
        closed = true
        try {
            // NonCancellable for the same reason as CodecConnection.close: the canonical call site is
            // `finally { close() }`, and a cancelled caller would otherwise abort at sink.close() —
            // the first suspension point — leaving the pool uncleared and its native memory held.
            withContext(NonCancellable) {
                // Bounded for the same reason as CodecConnection's stream close: unbounded work
                // inside NonCancellable is UNKILLABLE, and a uni-stream close is a command
                // round-trip to the QUIC driver loop. A wedged loop must not strand this teardown
                // with every other closer queued behind it on teardownFinished.
                withTimeoutOrNull(config.io.outboundDrainOnClose) { sink.close() }
                // Outside the timeout on purpose: the pool must be cleared even when the sink close
                // ran out of budget, or a dead peer would leak this sender's encode buffers.
                bufferPool.clear()
            }
        } finally {
            teardownFinished.complete(Unit)
        }
    }

    private companion object {
        private const val MAX_SEND_RESIZE_ATTEMPTS = 20
    }
}
