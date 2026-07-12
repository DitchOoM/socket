package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.concurrent.Volatile

/** Why a WebTransport session ended: the application error [code] and human-readable [reason]. */
data class WebTransportCloseInfo(
    val code: Int = 0,
    val reason: String = "",
)

/**
 * A WebTransport-over-HTTP/3 failure (RFC 9220 + draft-ietf-webtrans-http3) that isn't a plain H3 error.
 * The typed reason is [failure] (see [WebTransportFailure]); the exception [message] is rendered from it,
 * and any underlying transport error is chained as [cause]. Consumers switch on [failure] rather than
 * parsing the message.
 */
class WebTransportException internal constructor(
    val failure: WebTransportFailure,
    cause: Throwable? = null,
) : Exception(failure.describe(), cause)

/**
 * An established WebTransport session (RFC 9220 + draft-ietf-webtrans-http3), shared by the client
 * ([Http3Connection.connectWebTransport]) and server ([WebTransportServerExchange.accept]) roles.
 *
 * A session rides one HTTP/3 Extended CONNECT bidirectional stream — the **CONNECT stream**, whose
 * id is the [sessionId]. That stream stays open for the session's lifetime; its body carries the
 * RFC 9297 Capsule Protocol (graceful close, [close]/[awaitClosed]). WebTransport streams and
 * datagrams are associated with the session by this id.
 *
 * Once established a session multiplexes:
 *  - **streams** — [openBidiStream] / [openUniStream] to initiate, and
 *    [incomingBidiStreams] / [incomingUniStreams] for peer-initiated ones; and
 *  - **datagrams** — [sendDatagram] / [datagrams] (require the underlying QUIC connection to have
 *    datagrams enabled).
 *
 * Lifecycle: the session is open until [close]d locally or the peer ends the CONNECT stream;
 * [awaitClosed] suspends until then and [closeInfo] reports why.
 */
class WebTransportSession internal constructor(
    val sessionId: Long,
    internal val connectStream: QuicByteStream,
    private val mux: WebTransportMux,
) {
    /**
     * The connection's buffer factory (the underlying QUIC connection's native-memory factory; see
     * [com.ditchoom.socket.quic.network]). Allocate stream/datagram buffers from here, paired with
     * `use { }`.
     */
    val bufferFactory: BufferFactory get() = mux.bufferFactory

    @Volatile
    private var _closeInfo: WebTransportCloseInfo? = null

    /** Non-null once the session has ended (locally or by the peer); null while open. */
    val closeInfo: WebTransportCloseInfo? get() = _closeInfo
    val isClosed: Boolean get() = _closeInfo != null

    private val closedSignal = CompletableDeferred<WebTransportCloseInfo>()

    // true once the peer has requested a drain; false if the session ends with no drain ever seen.
    // Completing it on close lets the [drained] flow terminate instead of hanging a collector forever.
    private val drainSignal = CompletableDeferred<Boolean>()

    @Volatile
    private var _isDrainRequested = false

    @Volatile
    private var localDrainSent = false

    /** True once the peer has asked this session to drain (WT_DRAIN_SESSION, draft §5). */
    val isDrainRequested: Boolean get() = _isDrainRequested

    // Peer-initiated streams, fed by the connection's router via the mux. UNLIMITED: a burst of
    // incoming streams must never block the router thread.
    private val incomingBidi = Channel<WebTransportStream>(Channel.UNLIMITED)
    private val incomingUni = Channel<WebTransportReceiveStream>(Channel.UNLIMITED)

    // Inbound datagrams are unreliable: a bounded queue that drops the oldest on overflow (and frees the
    // dropped/undelivered buffer), matching QUIC datagram semantics.
    private val incomingDatagrams =
        Channel<ReadBuffer>(
            capacity = DATAGRAM_QUEUE,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { it.freeIfNeeded() },
        )

    /** Peer-initiated bidirectional WebTransport streams (draft-ietf-webtrans-http3 §4.2). */
    val incomingBidiStreams: Flow<WebTransportStream> get() = incomingBidi.receiveAsFlow()

    /** Peer-initiated unidirectional WebTransport streams (draft-ietf-webtrans-http3 §4.1). */
    val incomingUniStreams: Flow<WebTransportReceiveStream> get() = incomingUni.receiveAsFlow()

    /**
     * Inbound WebTransport datagrams (draft-ietf-webtrans-http3 §4.4). Each emitted buffer is owned by
     * the collector (release via `freeIfNeeded()`). Empty unless QUIC datagrams are enabled on the
     * connection; unreliable — the oldest queued datagram is dropped under pressure.
     */
    val datagrams: Flow<ReadBuffer> get() = incomingDatagrams.receiveAsFlow()

    /**
     * Emits exactly once when the peer asks this session to drain (WT_DRAIN_SESSION,
     * draft-ietf-webtrans-http3 §5): the peer is winding the session down and the application should
     * stop opening new streams, though in-flight streams and datagrams still finish until [close].
     * Completes without emitting if the session ends before any drain arrives.
     */
    val drained: Flow<Unit> get() = flow { if (drainSignal.await()) emit(Unit) }

    /** Suspends until the session ends, returning why. */
    suspend fun awaitClosed(): WebTransportCloseInfo = closedSignal.await()

    /** Open a bidirectional WebTransport stream on this session. */
    suspend fun openBidiStream(): WebTransportStream {
        check(!isClosed) { "session $sessionId is closed" }
        return mux.openBidi(sessionId)
    }

    /** Open a unidirectional (send-only) WebTransport stream on this session. */
    suspend fun openUniStream(): WebTransportSendStream {
        check(!isClosed) { "session $sessionId is closed" }
        return mux.openUni(sessionId)
    }

    /**
     * Send a WebTransport datagram (draft-ietf-webtrans-http3 §4.4) carrying [payload]'s remaining
     * bytes. Requires QUIC datagrams to be enabled on the connection (else [WebTransportException]).
     */
    suspend fun sendDatagram(payload: ReadBuffer) {
        check(!isClosed) { "session $sessionId is closed" }
        mux.sendDatagram(sessionId, payload)
    }

    /**
     * Ask the peer to wind this session down (WT_DRAIN_SESSION, draft-ietf-webtrans-http3 §5) **without**
     * closing it: send a WT_DRAIN_SESSION capsule on the CONNECT stream so the peer learns it should stop
     * opening new streams, while in-flight streams and datagrams still finish. The session stays open
     * until [close]. Idempotent, and a no-op once the session is closed.
     */
    suspend fun drain() {
        if (isClosed || localDrainSent) return
        localDrainSent = true
        try {
            mux.sendDrainCapsule(connectStream)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Connection/stream already gone — nothing left to wind down.
        }
    }

    /**
     * Close the session gracefully with an application [code] + [reason] (draft-ietf-webtrans-http3
     * §6): send a WT_CLOSE_SESSION capsule on the CONNECT stream and FIN it. Idempotent. The [reason]
     * is truncated to 1024 UTF-8 bytes.
     */
    suspend fun close(
        code: Int = 0,
        reason: String = "",
    ) {
        if (isClosed) return
        try {
            mux.sendCloseCapsule(connectStream, code, reason)
        } catch (_: Throwable) {
            // Connection/stream already gone — fall through to local teardown.
        }
        finish(WebTransportCloseInfo(code, reason))
    }

    // --- Internal: driven by the connection's router + the mux's capsule loop ---

    internal fun deliverIncomingBidi(stream: WebTransportStream) {
        incomingBidi.trySend(stream)
    }

    internal fun deliverIncomingUni(stream: WebTransportReceiveStream) {
        incomingUni.trySend(stream)
    }

    internal fun deliverDatagram(buffer: ReadBuffer) {
        incomingDatagrams.trySend(buffer) // DROP_OLDEST: always accepted; the dropped buffer is freed
    }

    /** Called by the mux's capsule loop when the peer sends WT_DRAIN_SESSION (draft §5). Idempotent. */
    internal fun onPeerDrain() {
        _isDrainRequested = true
        drainSignal.complete(true)
    }

    /** Called by the mux's capsule loop when the peer ends the CONNECT stream (FIN or close capsule). */
    internal suspend fun onPeerClosed(info: WebTransportCloseInfo) = finish(info)

    private suspend fun finish(info: WebTransportCloseInfo) {
        if (!closedSignal.complete(info)) return // already closed
        _closeInfo = info
        drainSignal.complete(false) // no-op if a drain already arrived; else unblocks [drained] collectors
        incomingBidi.close()
        incomingUni.close()
        incomingDatagrams.close()
        mux.deregister(sessionId)
    }

    private companion object {
        /** Inbound datagram queue depth before the oldest is dropped. */
        const val DATAGRAM_QUEUE = 256
    }
}
