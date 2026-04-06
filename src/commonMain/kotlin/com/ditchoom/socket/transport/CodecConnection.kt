package com.ditchoom.socket.transport

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.ConnectionContext
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.SocketClosedException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

class CodecConnection<T>(
    val stream: ByteStream,
    val codec: Codec<T>,
    val pool: BufferPool,
    private val options: ConnectionOptions = ConnectionOptions(),
    private val decodeContext: DecodeContext = DecodeContext.Empty,
    private val encodeContext: EncodeContext = EncodeContext.Empty,
    override val id: Long = 0L,
) : com.ditchoom.buffer.flow.Connection<T> {
    private val streamProcessor: StreamProcessor = StreamProcessor.create(pool)

    @Volatile
    private var closed = false

    @Volatile
    private var receiving = false

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

    override suspend fun send(message: T) {
        check(!closed) { "CodecConnection is closed" }
        val size =
            when (val estimate = codec.sizeOf(message)) {
                is com.ditchoom.buffer.codec.SizeEstimate.Exact -> estimate.bytes
                com.ditchoom.buffer.codec.SizeEstimate.UnableToPrecalculate -> options.defaultBufferSize
            }
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
        val frameSize =
            when (val result = codec.peekFrameSize(streamProcessor, 0)) {
                is PeekResult.Size -> result.bytes
                PeekResult.NeedsMoreData -> return null
            }
        if (streamProcessor.available() < frameSize) return null
        return streamProcessor.readBufferScoped(frameSize) { codec.decode(this, decodeContext) }
    }

    private suspend fun fillFromTransport(): Boolean =
        when (val result = stream.read(options.readTimeout)) {
            is ReadResult.Data -> {
                _lastDataReceived.value = TimeSource.Monotonic.markNow()
                streamProcessor.append(result.buffer)
                true
            }
            is ReadResult.End -> false
            is ReadResult.Reset ->
                throw SocketClosedException.ConnectionReset("Stream reset by peer")
        }

    override suspend fun close() {
        if (closed) return
        closed = true
        stream.close()
        streamProcessor.release()
    }

    companion object {
        suspend fun <T> connect(
            hostname: String,
            port: Int,
            codec: Codec<T>,
            transport: Transport = TcpTransport(),
            options: ConnectionOptions = ConnectionOptions(),
            decodeContext: DecodeContext = DecodeContext.Empty,
            encodeContext: EncodeContext = EncodeContext.Empty,
        ): CodecConnection<T> {
            val context = ConnectionContext(options)
            val stream = transport.connect(hostname, port, context)
            return CodecConnection(stream, codec, context.pool, options, decodeContext, encodeContext)
        }
    }
}
