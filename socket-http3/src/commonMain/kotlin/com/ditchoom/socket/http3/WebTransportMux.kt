package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * The per-connection WebTransport engine (draft-ietf-webtrans-http3), shared verbatim by the client
 * ([Http3Connection]) and server ([Http3ServerConnection]) roles. Owns the session table and the
 * WebTransport-specific framings that sit on top of the HTTP/3 QUIC connection:
 *
 *  - **opening** WebTransport streams ([openBidi]/[openUni]) — a fresh QUIC stream prefixed with the
 *    WebTransport signal/type + Session ID,
 *  - **demultiplexing** peer-opened WebTransport streams ([acceptIncomingBidi]/[acceptIncomingUni])
 *    onto the owning session's incoming-stream flows,
 *  - **datagrams** ([sendDatagram] + the [startDatagramLoop] receive pump) using the RFC 9297 Quarter
 *    Stream ID framing, and
 *  - the **Capsule Protocol** on each session's CONNECT stream ([runCapsuleLoop]) — reading
 *    DATA-framed capsules to surface a peer's graceful close, and [sendCloseCapsule] to send our own.
 *
 * Stream/datagram demux looks a session up by id; an unknown or already-closed session means the frame
 * is dropped (the stream reset). Sessions are registered the moment their CONNECT stream id is known
 * (before the 2xx is even read on the client), so a peer that opens a WebTransport stream immediately
 * after the handshake never races ahead of registration.
 */
internal class WebTransportMux(
    private val scope: QuicScope,
    private val pool: BufferPool,
    private val config: TransportConfig,
) {
    private val sessions = mutableMapOf<Long, WebTransportSession>()
    private val mutex = Mutex()

    /** The connection's buffer factory — surfaced to [WebTransportSession.bufferFactory]. */
    val bufferFactory: BufferFactory get() = scope.bufferFactory

    /**
     * Create a session for a CONNECT stream and table it immediately by id, before the handshake
     * completes. [abandon] removes it if the CONNECT is rejected; [activate] starts its capsule loop
     * once it is confirmed.
     */
    suspend fun preRegister(connectStream: QuicByteStream): WebTransportSession {
        val session = WebTransportSession(connectStream.streamId.id, connectStream, this)
        mutex.withLock { sessions[session.sessionId] = session }
        return session
    }

    /** Start [session]'s CONNECT-stream capsule loop (call once the session is confirmed established). */
    fun activate(
        session: WebTransportSession,
        reader: Http3StreamReader,
    ) {
        scope.launch { runCapsuleLoop(session, reader) }
    }

    /** Drop a session whose CONNECT was rejected/aborted before it was activated. */
    suspend fun abandon(session: WebTransportSession) {
        mutex.withLock { sessions.remove(session.sessionId) }
    }

    /** Connection-level deregistration, called from [WebTransportSession.close]/peer-close. */
    suspend fun deregister(sessionId: Long) {
        mutex.withLock { sessions.remove(sessionId) }
    }

    private suspend fun session(sessionId: Long): WebTransportSession? = mutex.withLock { sessions[sessionId] }

    /** Number of sessions currently in the table — the server gates inbound accepts on this. */
    suspend fun activeCount(): Int = mutex.withLock { sessions.size }

    // --- Opening WebTransport streams (draft-ietf-webtrans-http3 §4.1 / §4.2) ---

    /** Open a bidirectional WebTransport stream: a QUIC bidi stream prefixed with `0x41` + Session ID. */
    suspend fun openBidi(sessionId: Long): WebTransportStream {
        val stream = scope.openStream()
        writeStreamHeader(stream, WebTransportWire.WT_BIDI_STREAM_SIGNAL, sessionId)
        return WebTransportStream(sessionId, stream, pending = null)
    }

    /** Open a unidirectional WebTransport stream: a QUIC uni stream prefixed with `0x54` + Session ID. */
    suspend fun openUni(sessionId: Long): WebTransportSendStream {
        val stream = scope.openUniStream()
        writeStreamHeader(stream, WebTransportWire.WT_UNI_STREAM_TYPE, sessionId)
        return WebTransportSendStream(sessionId, stream)
    }

    private suspend fun writeStreamHeader(
        stream: QuicByteStream,
        prefix: Long,
        sessionId: Long,
    ) {
        val buffer = pool.allocate(VarIntCodec.encodedLength(prefix) + VarIntCodec.encodedLength(sessionId))
        try {
            VarIntCodec.encode(buffer, prefix, EncodeContext.Empty)
            VarIntCodec.encode(buffer, sessionId, EncodeContext.Empty)
            buffer.resetForRead()
            // Adapter rule: no-arg write() consults the leaf stream's writePolicy rather than clobbering
            // it with config — the stream owns the deadline policy for its direction.
            stream.write(buffer)
        } finally {
            buffer.freeIfNeeded()
        }
    }

    // --- Demultiplexing peer-opened WebTransport streams ---

    /**
     * Take ownership of a peer-opened **bidirectional** stream whose leading `0x41` signal was just
     * observed: read the Session ID, hand the stream (with any bytes already buffered after the header)
     * to the owning session's [WebTransportSession.incomingBidiStreams], or reset it if no live session
     * owns it. Always consumes [processor]; the caller must not touch [stream]/[processor] afterward.
     */
    suspend fun acceptIncomingBidi(
        stream: QuicByteStream,
        processor: StreamProcessor,
    ) {
        val reader = Http3StreamReader(stream, processor)
        reader.nextVarInt(config.readPolicy.toDeadline()) // the 0x41 signal (already peeked by the router)
        val sessionId = reader.nextVarInt(config.readPolicy.toDeadline())
        val session = session(sessionId)
        if (session == null || session.isClosed) {
            processor.release()
            resetQuietly(stream)
            return
        }
        val pending = drainBuffered(processor)
        processor.release()
        val wt = WebTransportStream(sessionId, stream, pending)
        session.deliverIncomingBidi(wt)
    }

    /**
     * Take ownership of a peer-opened **unidirectional** stream whose `0x54` type prefix was just
     * consumed: read the Session ID, hand the receive stream to the owning session's
     * [WebTransportSession.incomingUniStreams], or reset it if no live session owns it. Always consumes
     * [processor].
     */
    suspend fun acceptIncomingUni(
        stream: QuicByteStream,
        processor: StreamProcessor,
    ) {
        val reader = Http3StreamReader(stream, processor)
        val sessionId = reader.nextVarInt(config.readPolicy.toDeadline())
        val session = session(sessionId)
        if (session == null || session.isClosed) {
            processor.release()
            resetQuietly(stream)
            return
        }
        val pending = drainBuffered(processor)
        processor.release()
        val wt = WebTransportReceiveStream(sessionId, stream, pending)
        session.deliverIncomingUni(wt)
    }

    /** Copy whatever bytes the [processor] still holds into an owned buffer (or null when empty). */
    private fun drainBuffered(processor: StreamProcessor): ReadBuffer? {
        val n = processor.available()
        if (n == 0) return null
        val copy = pool.allocate(n)
        // Scoped: everything is copied into `copy`, so the wire bytes recycle immediately.
        processor.readBufferScoped(n) { copy.write(this) }
        copy.resetForRead()
        return copy
    }

    // --- Datagrams (RFC 9297 §2.1 Quarter Stream ID framing) ---

    /**
     * Send a WebTransport datagram for [sessionId]: a QUIC DATAGRAM of `Quarter Stream ID` followed by
     * [payload]'s bytes (draft-ietf-webtrans-http3 §4.4). Throws [WebTransportException] if the
     * underlying QUIC connection has no datagram support enabled.
     */
    suspend fun sendDatagram(
        sessionId: Long,
        payload: ReadBuffer,
    ) {
        val quarter = WebTransportWire.quarterStreamId(sessionId)
        val out = pool.allocate(VarIntCodec.encodedLength(quarter) + payload.remaining())
        try {
            VarIntCodec.encode(out, quarter, EncodeContext.Empty)
            if (payload.remaining() > 0) out.write(payload)
            out.resetForRead()
            try {
                scope.sendDatagram(out)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw WebTransportException("QUIC datagrams are not enabled on this connection: ${e.message}")
            }
        } finally {
            out.freeIfNeeded()
        }
    }

    /**
     * Pump inbound QUIC datagrams to their session's [WebTransportSession.datagrams] flow, keyed by the
     * Quarter Stream ID. A datagram for an unknown/closed session is dropped. Collecting
     * [QuicScope.datagrams] completes immediately (does nothing) when datagrams are not enabled, so this
     * is safe to launch unconditionally for a WebTransport-enabled connection.
     */
    fun startDatagramLoop() {
        scope.launch {
            try {
                scope.datagrams().collect { buffer ->
                    val sessionId =
                        try {
                            VarIntCodec.decode(buffer, DecodeContext.Empty) * 4
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            buffer.freeIfNeeded() // malformed Quarter Stream ID
                            return@collect
                        }
                    val session = session(sessionId)
                    if (session != null && !session.isClosed) {
                        session.deliverDatagram(buffer) // takes ownership (frees on overflow)
                    } else {
                        buffer.freeIfNeeded()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Connection closed / datagram source ended — nothing more to demux.
            }
        }
    }

    // --- Capsule Protocol on the CONNECT stream (RFC 9297, carried in HTTP/3 DATA frames) ---

    /**
     * Read the session's CONNECT stream as a Capsule Protocol byte-stream (the concatenation of its
     * DATA-frame payloads, RFC 9297 §3.1) until the stream ends or a WT_CLOSE_SESSION capsule arrives,
     * then end the session. A WT_CLOSE_SESSION carries the peer's application close code + reason; a
     * bare FIN (or any error) ends the session with the default close info. Owns [reader]'s release.
     */
    private suspend fun runCapsuleLoop(
        session: WebTransportSession,
        reader: Http3StreamReader,
    ) {
        val capsules = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            loop@ while (true) {
                val frame = reader.nextFrame(Duration.INFINITE) ?: break
                if (frame is Http3Frame.Data) {
                    appendCopy(capsules, frame.payload)
                    if (parseCapsules(capsules, session)) break@loop // a close capsule was seen
                }
                // Non-DATA frames on a CONNECT/Capsule stream are ignored (RFC 9297 treats the content as
                // the capsule byte-stream; HTTP/3 reserved frames are skipped per RFC 9114 §9).
            }
        } catch (_: Throwable) {
            // Stream error / reset / connection close — the session is gone.
        } finally {
            capsules.release()
            reader.release()
            session.onPeerClosed(session.closeInfo ?: WebTransportCloseInfo())
            deregister(session.sessionId)
        }
    }

    /** Append a copy of [payload]'s remaining bytes to [capsules] (the borrowed frame buffer is transient). */
    private fun appendCopy(
        capsules: StreamProcessor,
        payload: ReadBuffer,
    ) {
        val n = payload.remaining()
        if (n == 0) return
        val copy = pool.allocate(n)
        copy.write(payload)
        copy.resetForRead()
        capsules.append(copy)
    }

    /**
     * Parse and act on every whole capsule currently buffered in [capsules]. Returns true if a
     * WT_CLOSE_SESSION capsule was processed (the caller should stop reading). Unknown capsule types
     * are skipped (their length is honoured so parsing stays aligned).
     */
    private suspend fun parseCapsules(
        capsules: StreamProcessor,
        session: WebTransportSession,
    ): Boolean {
        while (true) {
            // Framing is the pure, fuzzable [WebTransportWire.nextCapsule]; the mux only owns the
            // session dispatch, which suspends and so must stay outside the readBufferScoped window.
            when (val capsule = WebTransportWire.nextCapsule(capsules)) {
                CapsuleParse.NeedMore -> return false
                is CapsuleParse.Close -> {
                    session.onPeerClosed(capsule.info)
                    return true
                }
                CapsuleParse.Drain -> {
                    // The peer is winding the session down (draft §5); surface it but keep the session
                    // open so in-flight streams/datagrams finish.
                    session.onPeerDrain()
                }
                // Unknown capsule types: the value bytes were consumed inside nextCapsule; continue.
                CapsuleParse.Skipped -> {}
            }
        }
    }

    /**
     * Send a WT_CLOSE_SESSION capsule (draft-ietf-webtrans-http3 §6) inside a DATA frame on [connectStream]
     * then FIN it — the graceful WebTransport close. The reason is truncated to 1024 UTF-8 bytes.
     */
    suspend fun sendCloseCapsule(
        connectStream: QuicByteStream,
        code: Int,
        reason: String,
    ) {
        val reasonBytes = qpackUtf8ByteLength(reason).coerceAtMost(WebTransportWire.MAX_CLOSE_REASON_BYTES)
        // If truncation would split a multi-byte character, fall back to no reason (the code still carries).
        val safeReason = if (reasonBytes == qpackUtf8ByteLength(reason)) reason else ""
        val safeReasonBytes = if (safeReason.isEmpty()) 0 else reasonBytes
        val capsuleSize = WebTransportWire.closeSessionCapsuleSize(safeReasonBytes)
        val capsule = pool.allocate(capsuleSize)
        try {
            WebTransportWire.writeCloseSessionCapsule(capsule, code, safeReason, safeReasonBytes)
            capsule.resetForRead()
            writeDataFrame(connectStream, capsule)
        } finally {
            capsule.freeIfNeeded()
        }
        connectStream.shutdownSend()
    }

    /**
     * Send a WT_DRAIN_SESSION capsule (draft-ietf-webtrans-http3 §5) inside a DATA frame on
     * [connectStream] **without** FIN-ing it: the session stays open so in-flight streams/datagrams can
     * still finish, while the peer learns we are winding down. Mirrors [sendCloseCapsule] minus the
     * shutdown.
     */
    suspend fun sendDrainCapsule(connectStream: QuicByteStream) {
        val capsule = pool.allocate(WebTransportWire.drainSessionCapsuleSize())
        try {
            WebTransportWire.writeDrainSessionCapsule(capsule)
            capsule.resetForRead()
            writeDataFrame(connectStream, capsule)
        } finally {
            capsule.freeIfNeeded()
        }
    }

    /** Wrap [payload] in an HTTP/3 DATA frame and write it whole to [stream]. */
    private suspend fun writeDataFrame(
        stream: QuicByteStream,
        payload: ReadBuffer,
    ) {
        val frame = Http3Frame.Data(payload)
        // The generated framed encode owns allocation (slicing scheme over the
        // pool) and returns a ReadBuffer spanning exactly the frame's wire bytes.
        val buffer = Http3FrameCodec.encode(frame, EncodeContext.Empty, pool)
        try {
            // Adapter rule: no-arg write() consults the CONNECT stream's writePolicy, not config.
            stream.write(buffer)
        } finally {
            buffer.freeIfNeeded()
        }
    }

    private suspend fun resetQuietly(stream: QuicByteStream) {
        try {
            stream.reset(0)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Already gone.
        }
    }
}
