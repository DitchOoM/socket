package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.Sender
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.TransportConfig
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

    @Volatile
    private var closed = false

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
                // Adapter rule: propagate, don't clobber. Call the leaf's no-arg write() so its
                // injected writePolicy governs the deadline — never inject our own.
                sink.write(buffer)
                return
            } catch (e: BufferOverflowException) {
                buffer.freeIfNeeded()
                if (attempts++ >= MAX_SEND_RESIZE_ATTEMPTS) throw e
                capacity = (capacity.toLong() * 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                continue
            } catch (t: Throwable) {
                buffer.freeIfNeeded()
                throw t
            }
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        sink.close()
        bufferPool.clear()
    }

    private companion object {
        private const val MAX_SEND_RESIZE_ATTEMPTS = 20
    }
}
