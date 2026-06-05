package com.ditchoom.socket.http3

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * The **server** side of an HTTP/3 connection (RFC 9114 §3.2) — the production counterpart to the
 * client [Http3Connection], run once per accepted QUIC connection by [withHttp3Server].
 *
 * [serve] opens the server control unidirectional stream (its first frame is the server SETTINGS),
 * optionally the QPACK encoder/decoder streams when [qpackCapacity] > 0, then routes peer-initiated
 * streams: the client control stream's SETTINGS sizes the QPACK encoder; the client QPACK streams
 * drive the decoder/encoder; and each client **bidirectional** stream is one request — decoded into an
 * [Http3ServerRequest], paired with an [Http3ServerResponse], and handed to [onRequest]. After the
 * handler returns, any unread request body is drained and the response is FIN'd.
 *
 * With [qpackCapacity] > 0 the server speaks dynamic QPACK (RFC 9204) in both directions: it decodes
 * requests through a [QpackDecoder] fed by the client's encoder stream and compresses responses through
 * a [QpackEncoder]. With capacity 0 (default) it stays static-table-only, which is always legal.
 *
 * This server does not initiate server push (RFC 9114 §4.6) — that is the inverse of the client push
 * support and a tracked follow-up; the client push API in [Http3Connection.pushes] is independent.
 */
class Http3ServerConnection internal constructor(
    private val scope: QuicScope,
    private val options: ConnectionOptions,
    private val qpackCapacity: Long,
    private val onRequest: suspend Http3ServerExchange.() -> Unit,
) {
    // MultiThreaded: per-stream handler coroutines allocate from this pool concurrently.
    private val pool = BufferPool(threadingMode = ThreadingMode.MultiThreaded, factory = options.bufferFactory)

    private val serverDecoder: QpackDecoder? =
        if (qpackCapacity > 0) QpackDecoder(qpackCapacity) { writeQpackDecoderInstruction(it) } else null

    @Volatile
    private var serverEncoder: QpackEncoder? = null
    private var qpackEncoderStream: QuicByteStream? = null
    private var qpackDecoderStream: QuicByteStream? = null
    private val encoderStreamWriteMutex = Mutex()
    private val decoderStreamWriteMutex = Mutex()

    // Server-push state (RFC 9114 §4.6). clientMaxPushId is the largest Push ID the client will accept,
    // learned from its MAX_PUSH_ID frame(s) on the control stream (-1 = push not enabled). The server
    // allocates push ids 0,1,… up to that limit. Guarded by pushIdMutex.
    @Volatile
    private var clientMaxPushId: Long = -1
    private val pushIdMutex = Mutex()
    private var nextPushId: Long = 0

    /** Run the HTTP/3 server role over [scope] (one accepted QUIC connection). Returns when it closes. */
    suspend fun serve() {
        writeControlStreamHeader(scope.openUniStream())
        if (qpackCapacity > 0) {
            qpackEncoderStream = scope.openUniStream().also { writeStreamType(it, Http3StreamType.QPACK_ENCODER) }
            qpackDecoderStream = scope.openUniStream().also { writeStreamType(it, Http3StreamType.QPACK_DECODER) }
        }
        scope.streams().collect { stream ->
            scope.launch {
                try {
                    if (stream.streamId.isUnidirectional) handleUniStream(stream) else handleRequest(stream)
                } catch (_: Http3StreamException) {
                    // A single stream failing must not take the connection down.
                }
            }
        }
    }

    private suspend fun handleUniStream(stream: QuicByteStream) {
        val processor = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            when (Http3StreamReader(stream, processor).nextVarInt()) {
                Http3StreamType.CONTROL -> handleControl(Http3StreamReader(stream, processor))
                Http3StreamType.QPACK_ENCODER ->
                    serverDecoder?.let { dec ->
                        val reader = QpackInstructionReader.encoder(stream, processor, pool)
                        while (true) dec.applyEncoderInstruction(reader.next(options.readTimeout) ?: break)
                    } ?: drain(stream)
                Http3StreamType.QPACK_DECODER ->
                    if (serverDecoder != null) {
                        val reader = QpackInstructionReader.decoder(stream, processor)
                        while (true) serverEncoder?.processDecoderInstruction(reader.next(options.readTimeout) ?: break)
                    } else {
                        drain(stream)
                    }
                else -> drain(stream)
            }
        } finally {
            processor.release()
        }
    }

    /** Read the client control stream: its SETTINGS sizes our encoder; ignore subsequent frames. */
    private suspend fun handleControl(reader: Http3StreamReader) {
        val first = reader.nextFrame()
        if (qpackCapacity > 0 && first is Http3Frame.Settings) {
            val clientMax = Http3Settings(first.entries).qpackMaxTableCapacity
            if (clientMax > 0) {
                serverEncoder =
                    QpackEncoder(clientMax) { writeQpackEncoderInstruction(it) }
                        .also { it.setCapacity(minOf(qpackCapacity, clientMax)) }
            }
        }
        // Capture MAX_PUSH_ID so the server knows whether (and how much) it may push (RFC 9114 §7.2.7);
        // it is non-decreasing, so just take the latest. Other control frames are accepted + ignored.
        while (true) {
            val frame = reader.nextFrame() ?: break
            if (frame is Http3Frame.MaxPushId) clientMaxPushId = frame.pushId
        }
    }

    /** Read one request off a client bidi [stream], run [onRequest], then drain the body + FIN. */
    private suspend fun handleRequest(stream: QuicByteStream) {
        val reader = Http3StreamReader.create(stream, pool)
        try {
            val first =
                reader.nextFrame(options.readTimeout)
                    ?: throw Http3StreamException("request stream ended before a HEADERS frame", Http3ErrorCode.REQUEST_INCOMPLETE)
            if (first !is Http3Frame.Headers) {
                throw Http3StreamException(
                    "request's first frame was ${first::class.simpleName}, expected HEADERS",
                    Http3ErrorCode.FRAME_UNEXPECTED,
                )
            }
            val streamId = stream.streamId.id
            val fields = decodeSection(first.encodedFieldSection, streamId)
            val request =
                Http3ServerRequest(
                    method = pseudo(fields, ":method"),
                    scheme = pseudo(fields, ":scheme"),
                    authority = pseudo(fields, ":authority"),
                    path = pseudo(fields, ":path"),
                    headers = fields.filterNot { it.name.startsWith(":") },
                    reader = reader,
                    pool = pool,
                    readTimeout = options.readTimeout,
                    decodeFields = { decodeSection(it, streamId) },
                )
            val response =
                Http3ServerResponse(stream, pool, options, streamId) { sectionFields, sid -> encodeSection(sectionFields, sid) }
            val exchange =
                Http3ServerExchange(request, response) { spec, respond -> pushResource(stream, spec, respond) }
            try {
                exchange.onRequest()
            } finally {
                runCatching { request.drain() }
                runCatching { response.finish() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Http3StreamException) {
            reactToRequestError(stream, e)
            throw e
        } finally {
            reader.release()
        }
    }

    /**
     * React to a violation while reading a request (RFC 9114 §8): a malformed *message*
     * ([Http3ErrorCode.MESSAGE_ERROR]) resets just this stream; an invalid frame *sequence*
     * ([Http3ErrorCode.FRAME_UNEXPECTED]) is a connection error, so the whole connection is closed
     * with the code. Best-effort — a failure because the stream/connection is already gone is ignored.
     */
    private suspend fun reactToRequestError(
        stream: QuicByteStream,
        error: Http3StreamException,
    ) {
        try {
            when (error.errorCode) {
                Http3ErrorCode.FRAME_UNEXPECTED -> scope.closeWithError(error.errorCode)
                else -> stream.reset(error.errorCode)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Already torn down.
        }
    }

    /** Encode a field section through the dynamic encoder (when present) or the static codec. */
    private suspend fun encodeSection(
        fields: List<QpackHeaderField>,
        streamId: Long,
    ): ReadBuffer {
        val encoder = serverEncoder
        return if (encoder != null) {
            encoder.encodeSection(fields, streamId, pool)
        } else {
            val size = (QpackFieldSectionCodec.wireSize(fields, EncodeContext.Empty) as WireSize.Exact).bytes
            pool.allocate(size).also {
                QpackFieldSectionCodec.encode(it, fields, EncodeContext.Empty)
                it.resetForRead()
            }
        }
    }

    /** Decode a field section through the dynamic decoder (when present) or the static codec. */
    private suspend fun decodeSection(
        section: ReadBuffer,
        streamId: Long,
    ): List<QpackHeaderField> {
        val decoder = serverDecoder
        return if (decoder != null) {
            decoder.decodeSection(section, streamId, pool)
        } else {
            QpackFieldSectionCodec.decode(section, DecodeContext.Empty.with(QpackScratchPoolKey, pool))
        }
    }

    // --- Server push (RFC 9114 §4.6), driven by Http3ServerExchange.push ---

    /**
     * Push a resource for the request on [requestStream]: allocate a Push ID within the client's
     * MAX_PUSH_ID limit, send a PUSH_PROMISE (the promised request fields) on the request stream, then
     * open a push stream (`0x01` + Push ID) and run [respond] to write the pushed response on it. Returns
     * false — without sending anything — when push is disabled or out of credit. Sent synchronously: the
     * pushed response is fully written before this returns, so call it before finishing the main response.
     */
    private suspend fun pushResource(
        requestStream: QuicByteStream,
        spec: PushPromiseSpec,
        respond: suspend Http3ServerResponse.() -> Unit,
    ): Boolean {
        val pushId =
            pushIdMutex.withLock {
                if (clientMaxPushId < 0 || nextPushId > clientMaxPushId) return false
                nextPushId++
            }
        val promiseFields =
            buildList {
                add(QpackHeaderField(":method", spec.method))
                add(QpackHeaderField(":scheme", spec.scheme))
                add(QpackHeaderField(":authority", spec.authority))
                add(QpackHeaderField(":path", spec.path))
                addAll(spec.promisedHeaders)
            }
        // PUSH_PROMISE rides the request stream; its field section uses that stream's QPACK context.
        val section = encodeSection(promiseFields, requestStream.streamId.id)
        try {
            writeFrame(requestStream, Http3Frame.PushPromise(pushId, section))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            section.freeIfNeeded()
            return false // request stream already finished/reset — nothing was promised
        }
        section.freeIfNeeded()

        val pushStream = scope.openUniStream()
        writePushStreamHeader(pushStream, pushId)
        val pushResponse =
            Http3ServerResponse(pushStream, pool, options, pushStream.streamId.id) { fields, sid -> encodeSection(fields, sid) }
        pushResponse.respond()
        pushResponse.finish()
        return true
    }

    /** Push stream header (RFC 9114 §4.6): the `0x01` stream-type prefix followed by the Push ID. */
    private suspend fun writePushStreamHeader(
        stream: QuicByteStream,
        pushId: Long,
    ) {
        val buffer = pool.allocate(VarIntCodec.encodedLength(Http3StreamType.PUSH) + VarIntCodec.encodedLength(pushId))
        try {
            VarIntCodec.encode(buffer, Http3StreamType.PUSH, EncodeContext.Empty)
            VarIntCodec.encode(buffer, pushId, EncodeContext.Empty)
            buffer.resetForRead()
            stream.write(buffer, options.writeTimeout)
        } finally {
            buffer.freeIfNeeded()
        }
    }

    /** Encode [frame] into a pooled buffer and write the whole frame to [stream]. */
    private suspend fun writeFrame(
        stream: QuicByteStream,
        frame: Http3Frame,
    ) {
        val size = (Http3FrameCodec.wireSize(frame, EncodeContext.Empty) as WireSize.Exact).bytes
        val buffer = pool.allocate(size)
        try {
            Http3FrameCodec.encode(buffer, frame, EncodeContext.Empty)
            buffer.resetForRead()
            stream.write(buffer, options.writeTimeout)
        } finally {
            buffer.freeIfNeeded()
        }
    }

    /** Control stream: the type prefix `0x00` immediately followed by the SETTINGS frame. */
    private suspend fun writeControlStreamHeader(control: QuicByteStream) {
        val settings =
            Http3Frame.Settings(
                listOf(
                    Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, qpackCapacity),
                    Http3Setting(Http3SettingId.QPACK_BLOCKED_STREAMS, if (qpackCapacity > 0) BLOCKED_STREAMS else 0L),
                ),
            )
        val frameSize = (Http3FrameCodec.wireSize(settings, EncodeContext.Empty) as WireSize.Exact).bytes
        val buffer = pool.allocate(VarIntCodec.encodedLength(Http3StreamType.CONTROL) + frameSize)
        try {
            VarIntCodec.encode(buffer, Http3StreamType.CONTROL, EncodeContext.Empty)
            Http3FrameCodec.encode(buffer, settings, EncodeContext.Empty)
            buffer.resetForRead()
            control.write(buffer, options.writeTimeout)
        } finally {
            buffer.freeIfNeeded()
        }
    }

    private suspend fun writeStreamType(
        stream: QuicByteStream,
        type: Long,
    ) {
        val buffer = pool.allocate(VarIntCodec.encodedLength(type))
        try {
            VarIntCodec.encode(buffer, type, EncodeContext.Empty)
            buffer.resetForRead()
            stream.write(buffer, options.writeTimeout)
        } finally {
            buffer.freeIfNeeded()
        }
    }

    private suspend fun writeQpackEncoderInstruction(instruction: QpackEncoderInstruction) {
        val stream = qpackEncoderStream ?: return
        val capacity =
            when (instruction) {
                is QpackEncoderInstruction.InsertWithNameRef -> 32 + qpackUtf8ByteLength(instruction.value)
                is QpackEncoderInstruction.InsertWithLiteralName ->
                    32 + qpackUtf8ByteLength(instruction.name) + qpackUtf8ByteLength(instruction.value)
                else -> 32
            }
        val buffer = pool.allocate(capacity)
        try {
            QpackEncoderInstructionCodec.encode(buffer, instruction)
            buffer.resetForRead()
            encoderStreamWriteMutex.withLock { stream.write(buffer, options.writeTimeout) }
        } finally {
            buffer.freeIfNeeded()
        }
    }

    private suspend fun writeQpackDecoderInstruction(instruction: QpackDecoderInstruction) {
        val stream = qpackDecoderStream ?: return
        val buffer = pool.allocate(16)
        try {
            QpackDecoderInstructionCodec.encode(buffer, instruction)
            buffer.resetForRead()
            decoderStreamWriteMutex.withLock { stream.write(buffer, options.writeTimeout) }
        } finally {
            buffer.freeIfNeeded()
        }
    }

    private suspend fun drain(stream: QuicByteStream) {
        while (true) {
            when (val result = stream.read(options.readTimeout)) {
                is ReadResult.Data -> result.buffer.freeIfNeeded()
                ReadResult.End, ReadResult.Reset -> return
            }
        }
    }

    private fun pseudo(
        fields: List<QpackHeaderField>,
        name: String,
    ): String =
        fields.firstOrNull { it.name == name }?.value
            ?: throw Http3StreamException("request HEADERS missing the $name pseudo-header", Http3ErrorCode.MESSAGE_ERROR)

    companion object {
        /** Blocked-streams we permit the client's encoder to create when dynamic QPACK is on. */
        private const val BLOCKED_STREAMS: Long = 100
    }
}
