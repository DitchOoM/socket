package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.Resettable
import kotlinx.coroutines.flow.Flow

/**
 * Why a WebTransport session ended: the application [code] and human-readable [reason]. [code] is the
 * 32-bit unsigned WebTransport session error code (draft §5; the `unsigned long` the browser's
 * `WebTransportCloseInfo.closeCode` uses), hence [UInt].
 */
data class WebTransportCloseInfo(
    val code: UInt = 0u,
    val reason: String = "",
)

/** A WebTransport (RFC 9220 + draft-ietf-webtrans-http3) failure surfaced through the neutral API. */
open class WebTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A WebTransport **stream** was aborted by the peer (RESET_STREAM / STOP_SENDING,
 * draft-ietf-webtrans-http3 §4.3) — the **platform-neutral** stream-abort signal, thrown by [write]
 * (and [ByteSink.write]) on both backings so cross-platform code catches ONE type:
 *  - native → translated from socket-http3's stream abort (the code decoded out of the HTTP/3 error-code
 *    space), and
 *  - browser → mapped from the platform's `WebTransportError.streamErrorCode`.
 *
 * The READ side surfaces a peer reset as [com.ditchoom.buffer.flow.ReadResult.Reset] (the shared
 * buffer-flow signal) on both backings; this exception is the WRITE-side counterpart, carrying the
 * [errorCode] — the WebTransport application error code, a **32-bit unsigned** value (draft §4.3; the
 * same `unsigned long` the browser's `WebTransportError.streamErrorCode` uses), hence [UInt]. Every
 * backend resolves it on a real abort (quiche surfaces quiche's `out_error_code` on all three bindings,
 * Apple via `nw_quic_get_stream_application_error`, the browser via `streamErrorCode`), so it is
 * non-null — no "unknown code" sentinel.
 */
class WebTransportStreamException(
    val errorCode: UInt,
    message: String,
    cause: Throwable? = null,
) : WebTransportException(message, cause)

/**
 * An established WebTransport session (RFC 9220 + draft-ietf-webtrans-http3) — **platform-neutral**.
 *
 * This is the one type common code names. It is backed by:
 *  - jvm / android / native → an Extended-CONNECT session over [com.ditchoom.socket.http3] (real QUIC), and
 *  - the browser            → the platform's `WebTransport` object (HTTP/3 done inside the browser).
 *
 * Both backings expose the same capabilities — streams + datagrams + graceful close — so neither is a
 * throwing stub. Native-only *power* (many sessions over one held HTTP/3 connection) is not on this
 * interface; it lives on the separate sealed [WebTransportSupport.Multiplexed], reachable by smart-cast.
 *
 * ### Streams are the Phase-3a byte trichotomy (no WebTransport-specific stream types)
 * A WebTransport stream is just a stream:
 *  - **bidirectional** → [ByteStream] (it is additionally a [Resettable] and a half-closeable at runtime;
 *    reach those by `is` smart-cast when you need reset / send-side FIN),
 *  - **outgoing unidirectional** → [ByteSink] (and [Resettable] — `stream as Resettable` to abort),
 *  - **incoming unidirectional** → [ByteSource] (and [Resettable] — reset = cancel / `STOP_SENDING`).
 *
 * Returning the directional base type keeps the native adapter zero-copy (socket-http3's concrete
 * stream classes already implement these buffer interfaces, so they pass straight through) and keeps
 * the capability-by-type discipline: an ability you have is a type you can `is`-check, never a stub.
 */
interface WebTransportSession {
    /** True once the session has ended (locally via [close] or because the peer closed it). */
    val isClosed: Boolean

    /** Non-null once the session has ended; null while open. */
    val closeInfo: WebTransportCloseInfo?

    /** Suspends until the session ends, returning why. */
    suspend fun awaitClosed(): WebTransportCloseInfo

    /** Open a bidirectional WebTransport stream (draft §4.2). Resettable + half-closeable at runtime. */
    suspend fun openBidiStream(): ByteStream

    /** Open an outgoing unidirectional WebTransport stream (draft §4.1). Also [Resettable]. */
    suspend fun openUniStream(): ByteSink

    /** Peer-initiated bidirectional streams (draft §4.2). */
    val incomingBidiStreams: Flow<ByteStream>

    /** Peer-initiated unidirectional (receive-only) streams (draft §4.1). Each is also [Resettable]. */
    val incomingUniStreams: Flow<ByteSource>

    /**
     * Send a WebTransport datagram (draft §4.4) carrying [payload]'s remaining bytes (zero-copy; the
     * caller retains ownership). Unreliable, like the underlying QUIC DATAGRAM frame.
     *
     * **Apple limitation:** WebTransport datagrams are unavailable on Apple platforms (macOS/iOS/
     * tvOS/watchOS). Network.framework cannot carry a QUIC datagram flow and inbound streams on the
     * same connection (extracting the datagram flow suppresses all inbound stream delivery), and
     * HTTP/3 structurally requires inbound streams, so the stack keeps streams and drops datagrams
     * (see [com.ditchoom.socket.quic.DatagramStreamConflictPolicy] and issue #173). Calling this on
     * Apple throws. Use streams instead, or guard on the platform.
     */
    suspend fun sendDatagram(payload: ReadBuffer)

    /**
     * Inbound WebTransport datagrams (draft §4.4). Each emitted buffer is owned by the collector
     * (release via `freeIfNeeded()`); unreliable — the oldest queued datagram is dropped under pressure.
     *
     * **Apple limitation:** always empty on Apple platforms — see [sendDatagram].
     */
    val datagrams: Flow<ReadBuffer>

    /**
     * Close the session gracefully with an application [code] + [reason] (draft §6). Idempotent.
     */
    suspend fun close(
        code: UInt = 0u,
        reason: String = "",
    )
}
