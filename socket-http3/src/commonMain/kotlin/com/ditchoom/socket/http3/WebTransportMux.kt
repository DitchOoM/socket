@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.utf8Size
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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

    // Adapter rule: this mux defers to each leaf stream's own writePolicy rather than imposing
    // config's — see Http3WriteDeadline.
    private val streamWriter = Http3StreamWriter(pool, config, Http3WriteDeadline.LEAF)

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
        streamWriter.writeVarInts(stream, prefix, sessionId)
    }

    // --- Demultiplexing peer-opened WebTransport streams ---

    /**
     * Take ownership of a peer-opened **bidirectional** stream whose leading `0x41` signal was just
     * observed: read the Session ID, hand the stream (with any bytes already buffered after the header)
     * to the owning session's [WebTransportSession.incomingBidiStreams], or reset it if no live session
     * owns it. Always consumes [processor] and [stream] — on every exit, not only success (#496): the
     * routers flag the pair as this mux's *before* calling, so their own `finally` neither releases nor
     * closes, and a Session ID read that throws (its deadline expiring, the peer resetting the stream, a
     * FIN mid-varint) would otherwise leave the processor's buffered chunk out of the pool for good and
     * the peer's half of the stream open. The caller must not touch [stream]/[processor] afterward.
     */
    suspend fun acceptIncomingBidi(
        stream: QuicByteStream,
        processor: StreamProcessor,
    ) {
        var handedOver = false
        try {
            val reader = Http3StreamReader(stream, processor)
            reader.nextVarInt(config.readPolicy.toDeadline()) // the 0x41 signal (already peeked by the router)
            val sessionId = reader.nextVarInt(config.readPolicy.toDeadline())
            val session = session(sessionId)
            if (session == null || session.isClosed) {
                resetQuietly(stream)
                return
            }
            val wt = WebTransportStream(sessionId, stream, drainBuffered(processor))
            handedOver = true
            session.deliverIncomingBidi(wt)
        } catch (e: Throwable) {
            if (!handedOver) abandon(stream, e)
            throw e
        } finally {
            // On every path: whatever the processor still held was either copied into the stream's
            // pending buffer above or is going nowhere. Its chunks are the QUIC receive pool's leaves.
            processor.release()
        }
    }

    /**
     * Take ownership of a peer-opened **unidirectional** stream whose `0x54` type prefix was just
     * consumed: read the Session ID, hand the receive stream to the owning session's
     * [WebTransportSession.incomingUniStreams], or reset it if no live session owns it. Always consumes
     * [processor] and [stream] on every exit, exactly as [acceptIncomingBidi] does (#496).
     */
    suspend fun acceptIncomingUni(
        stream: QuicByteStream,
        processor: StreamProcessor,
    ) {
        var handedOver = false
        try {
            val reader = Http3StreamReader(stream, processor)
            val sessionId = reader.nextVarInt(config.readPolicy.toDeadline())
            val session = session(sessionId)
            if (session == null || session.isClosed) {
                resetQuietly(stream)
                return
            }
            val wt = WebTransportReceiveStream(sessionId, stream, drainBuffered(processor))
            handedOver = true
            session.deliverIncomingUni(wt)
        } catch (e: Throwable) {
            if (!handedOver) abandon(stream, e)
            throw e
        } finally {
            processor.release() // see acceptIncomingBidi
        }
    }

    /**
     * Dispose of a peer stream this mux could not hand to a session because reading its header threw
     * [cause] (#496). The peer is told with RESET_STREAM + STOP_SENDING carrying `H3_REQUEST_CANCELLED` —
     * the code RFC 9114 §4.1.1 gives for data no longer needed, and the one [Http3Connection]'s router
     * already uses for a peer stream that stalls before it can be routed (#477) — unless [cause] is this
     * coroutine's own cancellation rather than a read deadline, which gets the bare close a cancelled
     * router leaves too: a read deadline's `TimeoutCancellationException` leaves the coroutine active, a
     * cancellation does not. Quietly either way; the connection may already be gone.
     */
    private suspend fun abandon(
        stream: QuicByteStream,
        cause: Throwable,
    ) {
        if (cause is CancellationException && !currentCoroutineContext().isActive) {
            closeQuietly(stream)
        } else {
            resetQuietly(stream, Http3ErrorCode.REQUEST_CANCELLED)
        }
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
                scope.datagramChannel().send(out)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw WebTransportException(WebTransportFailure.DatagramsNotEnabled, cause = e)
            }
        } finally {
            out.freeIfNeeded()
        }
    }

    /**
     * Pump inbound QUIC datagrams to their session's [WebTransportSession.datagrams] flow, keyed by the
     * Quarter Stream ID (the WebTransport flow-id demux — the datagram analogue of stream muxing, kept
     * consumer-side per the [com.ditchoom.buffer.flow.DatagramMux] contract). A datagram for an
     * unknown/closed session is dropped. The channel receive parks harmlessly until a datagram arrives
     * or the connection ends, so this is safe to launch unconditionally for a WebTransport connection.
     */
    fun startDatagramLoop() {
        scope.launch {
            try {
                val channel = scope.datagramChannel()
                while (true) {
                    val received = channel.receive()
                    if (received !is DatagramReadResult.Received) break // Closed — connection ended
                    val buffer = received.datagram.payload
                    val sessionId =
                        try {
                            VarIntCodec.decode(buffer, DecodeContext.Empty) * 4
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            buffer.freeIfNeeded() // malformed Quarter Stream ID
                            continue
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
        } catch (e: CancellationException) {
            // Scope cancelled — propagate so this loop's coroutine finalizes as cancelled rather than
            // masking it as a clean peer close. The finally still runs the session cleanup below.
            throw e
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
        val reasonBytes = reason.utf8Size().coerceAtMost(WebTransportWire.MAX_CLOSE_REASON_BYTES)
        // If truncation would split a multi-byte character, fall back to no reason (the code still carries).
        val safeReason = if (reasonBytes == reason.utf8Size()) reason else ""
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
        streamWriter.writeFrame(stream, Http3Frame.Data(payload))
    }

    private suspend fun resetQuietly(
        stream: QuicByteStream,
        errorCode: Long = 0,
    ) {
        try {
            stream.reset(errorCode)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Already gone.
        }
    }

    private suspend fun closeQuietly(stream: QuicByteStream) {
        try {
            stream.close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Already gone.
        }
    }
}
