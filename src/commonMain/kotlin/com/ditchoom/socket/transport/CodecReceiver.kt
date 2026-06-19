package com.ditchoom.socket.transport

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Receiver
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlin.concurrent.Volatile

/**
 * Adapts a receive-only [ByteSource] to a typed [Receiver] using a [Codec] — the honest counterpart of
 * [CodecConnection] for a **unidirectional inbound** stream. [receive] decodes framed messages until
 * the peer FINs ([ReadResult.End]), at which point the flow completes (EOF). A peer reset surfaces as
 * a [SocketClosedException.ConnectionReset].
 *
 * There is no send side (it is a [ByteSource], not a [com.ditchoom.buffer.flow.ByteStream]); the
 * receive side discovers its end rather than announcing it, which is why there is no `close()` here.
 */
class CodecReceiver<T>(
    val source: ByteSource,
    val codec: Codec<T>,
    private val config: TransportConfig = TransportConfig(),
    private val decodeContext: DecodeContext = DecodeContext.Empty,
    val id: Long = 0L,
) : Receiver<T> {
    private val bufferPool: BufferPool = BufferPool(factory = config.bufferFactory)
    private val streamProcessor: StreamProcessor = StreamProcessor.create(bufferPool)

    @Volatile
    private var receiving = false

    /**
     * Returns a cold flow of decoded messages. Collecting it drives reads off the [source] until EOF.
     * Sequential collection is allowed; concurrent collection throws — two collectors would corrupt
     * the shared stream processor.
     */
    override fun receive(): Flow<T> =
        flow {
            check(!receiving) { "receive() is already being collected concurrently" }
            receiving = true
            try {
                emitDrainedFrames()
                while (fillFromTransport()) {
                    emitDrainedFrames()
                }
            } finally {
                receiving = false
                streamProcessor.release()
                bufferPool.clear()
            }
        }

    private suspend fun FlowCollector<T>.emitDrainedFrames() {
        generateSequence { drainFrame() }.forEach { emit(it) }
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
        // readPolicy governs the deadline (a WebTransport uni stream's UntilClosed survives).
        when (val result = source.read()) {
            is ReadResult.Data -> {
                streamProcessor.append(result.buffer)
                true
            }
            is ReadResult.End -> false
            is ReadResult.Reset ->
                throw SocketClosedException.ConnectionReset("Stream reset by peer")
        }
}
