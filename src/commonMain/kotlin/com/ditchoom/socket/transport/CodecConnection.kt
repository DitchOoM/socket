package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferOverflowException
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadResult
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
import kotlin.time.TimeSource

class CodecConnection<T>(
    val stream: ByteStream,
    val codec: Codec<T>,
    private val config: TransportConfig = TransportConfig(),
    private val decodeContext: DecodeContext = DecodeContext.Empty,
    private val encodeContext: EncodeContext = EncodeContext.Empty,
    override val id: Long = 0L,
) : com.ditchoom.buffer.flow.Connection<T> {
    private val bufferPool: BufferPool = BufferPool(factory = config.bufferFactory)
    private val streamProcessor: StreamProcessor = StreamProcessor.create(bufferPool)

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
                writeFully(buffer)
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

    /**
     * Write every byte of [buffer], resuming until it is drained.
     *
     * [ByteSink.write][com.ditchoom.buffer.flow.ByteSink.write] returns [BytesWritten] because a write
     * may be PARTIAL — its contract calls the post-write position "the resume point for a partial
     * write's residue". Most sinks here never exercise that: the NIO sockets loop internally until the
     * whole buffer is gone, and [MemoryTransport] copies it wholesale. A QUIC stream does: quiche's
     * `stream_send` buffers only as many bytes as the stream's flow-control credit allows at that
     * moment and reports the count (a fully blocked stream parks and retries inside the driver, but a
     * PARTIALLY open one returns early by design).
     *
     * Ignoring that count silently truncated the frame — and for a self-framing codec that is
     * corruption, not loss: the length header the peer already received still declares the full size,
     * so it keeps reading and consumes the packets that FOLLOW as this one's tail. One under-filled
     * write desynchronizes the stream permanently.
     *
     * Resume off the reported COUNT rather than off the cursor, so both sink shapes work: the position
     * is re-derived after every call, which neither double-advances a contract-compliant sink nor
     * strands one that leaves the cursor alone. The QUIC stream is the latter — it hands quiche the
     * buffer's native address and never moves the cursor — so counting is what makes this correct
     * there today, independent of whether that deviation is later reconciled.
     *
     * Adapter rule: propagate, don't clobber. Each iteration calls the leaf's no-arg `write()` so its
     * injected [writePolicy][com.ditchoom.buffer.flow.ByteSink.writePolicy] governs the deadline — the
     * policy bounds each underlying call, as it always has, not the loop as a whole.
     */
    private suspend fun writeFully(buffer: ReadBuffer) {
        while (buffer.remaining() > 0) {
            val before = buffer.position()
            val written = stream.write(buffer).count
            // No progress with bytes still pending would spin here forever. That is a broken sink,
            // not back-pressure (back-pressure blocks inside write), so fail loudly rather than hang
            // — and rather than return early, which is the truncation this exists to prevent.
            if (written <= 0) {
                throw SocketWriteStalledException(accepted = written, pending = buffer.remaining())
            }
            val resumeAt = before + written
            if (buffer.position() != resumeAt) buffer.position(resumeAt)
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

    override suspend fun close() {
        if (closed) return
        closed = true
        stream.close()
        streamProcessor.release()
        bufferPool.clear()
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
        ): CodecConnection<T> {
            val stream = transport.connect(hostname, port, config)
            return CodecConnection(stream, codec, config, decodeContext, encodeContext)
        }
    }
}
