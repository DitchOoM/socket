package com.ditchoom.socket.transport

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.SocketClosedException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Peek function type for determining frame size from buffered stream data.
 *
 * Generated codecs produce `peekFrameSize(stream: StreamProcessor, baseOffset: Int): Int?`.
 * Pass as: `MyCodec::peekFrameSize`.
 *
 * Returns the total frame size in bytes, or null if not enough data to determine.
 */
typealias PeekFrameSize = (stream: StreamProcessor, baseOffset: Int) -> Int?

class CodecConnection<T>(
    val stream: ByteStream,
    val codec: Codec<T>,
    private val peekFrameSize: PeekFrameSize? = null,
    val pool: BufferPool,
    private val options: ConnectionOptions = ConnectionOptions(),
    private val decodeContext: DecodeContext = DecodeContext.Empty,
    private val encodeContext: EncodeContext = EncodeContext.Empty,
) {
    private val streamProcessor: StreamProcessor = StreamProcessor.create(pool)

    /**
     * Pre-seeds the stream processor with leftover bytes from a prior protocol phase.
     *
     * Use this after a protocol upgrade (e.g., HTTP handshake → WebSocket framing)
     * where the handshake parser may have over-read into the next protocol's data.
     * Call before [receive] to ensure no bytes are lost during the transition.
     */
    fun preSeed(buffer: ReadBuffer) {
        streamProcessor.append(buffer)
    }

    fun receive(): Flow<T> =
        flow {
            while (true) {
                // Drain all complete frames from buffered data
                while (true) {
                    val message = drainFrame() ?: break
                    emit(message)
                }
                // Fill from transport — returns false on stream end
                if (!fillFromTransport()) return@flow
            }
        }

    suspend fun send(message: T) {
        val size = codec.sizeOf(message) ?: options.defaultBufferSize
        val buffer = pool.acquire(size)
        try {
            codec.encode(buffer, message, encodeContext)
            buffer.resetForRead()
            stream.write(buffer, options.writeTimeout)
        } finally {
            buffer.freeIfNeeded()
        }
    }

    private fun drainFrame(): T? {
        val peekFn = peekFrameSize ?: return null
        val frameSize = peekFn(streamProcessor, 0) ?: return null
        if (streamProcessor.available() < frameSize) return null
        return streamProcessor.readBufferScoped(frameSize) { codec.decode(this, decodeContext) }
    }

    private suspend fun fillFromTransport(): Boolean =
        when (val result = stream.read(options.readTimeout)) {
            is ReadResult.Data -> {
                streamProcessor.append(result.buffer)
                true
            }
            is ReadResult.End -> false
            is ReadResult.Reset ->
                throw SocketClosedException.ConnectionReset("Stream reset by peer")
        }

    suspend fun close() {
        stream.close()
        streamProcessor.release()
        pool.clear()
    }

    companion object {
        suspend fun <T> connect(
            hostname: String,
            port: Int,
            codec: Codec<T>,
            peekFrameSize: PeekFrameSize? = null,
            transport: Transport = TcpTransport(),
            options: ConnectionOptions = ConnectionOptions(),
            decodeContext: DecodeContext = DecodeContext.Empty,
            encodeContext: EncodeContext = EncodeContext.Empty,
        ): CodecConnection<T> {
            val pool =
                BufferPool(
                    maxPoolSize = options.maxPoolSize,
                    defaultBufferSize = options.defaultBufferSize,
                    factory = options.bufferFactory,
                )
            val pooledOptions =
                options.copy(
                    bufferFactory = PooledBufferFactory(pool, options.bufferFactory),
                )
            val stream = transport.connect(hostname, port, pooledOptions)
            return CodecConnection(stream, codec, peekFrameSize, pool, options, decodeContext, encodeContext)
        }
    }
}
