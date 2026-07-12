package com.ditchoom.socket.webtransport

/**
 * A typed WebTransport (RFC 9220 + draft-ietf-webtrans-http3) failure surfaced through the **neutral**
 * cross-platform API — the reason a [WebTransportException] is raised. This is the platform-neutral twin
 * of `com.ditchoom.socket.http3.WebTransportFailure`: the native backing maps the http3 failure onto one
 * of these by TYPE (never by parsing a message), and the browser backing maps the platform
 * `WebTransportError` onto one too, so common code reasons about a failure by **exhaustive `when`**.
 *
 * Kept a separate sealed type from the http3 one on purpose: the neutral module's `commonMain` has no
 * dependency on `socket-http3` (the browser targets exclude it), the same reason [WebTransportCloseInfo]
 * is duplicated across the two modules. The adapter ([WebTransportSupport] backings) is the single seam
 * that maps http3 → neutral.
 *
 * [describe] renders the exception message from the typed fields; the wire-visible message therefore
 * stays a strict superset of the old hand-written strings (nothing that read `.message` breaks).
 */
sealed interface WebTransportFailure {
    /** A human-readable diagnostic built from this failure's typed fields (used as the exception message). */
    fun describe(): String

    /** WebTransport was not enabled locally (no options at bootstrap) — a local misconfiguration. */
    data object NotEnabledLocally : WebTransportFailure {
        override fun describe(): String = "WebTransport is not enabled on this connection"
    }

    /** The peer did not advertise WebTransport support — a structural capability gap on the far end. */
    data object PeerDoesNotSupport : WebTransportFailure {
        override fun describe(): String = "the peer did not advertise WebTransport support"
    }

    /**
     * The server rejected the session-establishing request with a non-2xx HTTP [status] (RFC 9220). The
     * [status] is first-class so a consumer can distinguish e.g. a `401` auth rejection from a `404`
     * unknown path without parsing the message. (Browser backings may not surface an HTTP status for a
     * rejected connect — see [SessionError] / the backing's mapping notes.)
     */
    data class ConnectRejected(
        val status: Int,
    ) : WebTransportFailure {
        override fun describe(): String = "WebTransport session was rejected with status $status"
    }

    /** A datagram was sent but the underlying connection has no datagram support enabled. */
    data object DatagramsNotEnabled : WebTransportFailure {
        override fun describe(): String = "datagrams are not enabled on this WebTransport connection"
    }

    /**
     * A WebTransport **stream** was aborted by the peer (RESET_STREAM / STOP_SENDING) — carries the
     * 32-bit unsigned WebTransport application [errorCode] (draft §4.3). This is the failure behind
     * [WebTransportStreamException].
     */
    data class StreamAborted(
        val errorCode: UInt,
    ) : WebTransportFailure {
        override fun describe(): String = "WebTransport stream aborted by peer (code $errorCode)"
    }

    /**
     * The TLS/QUIC handshake failed while establishing the session. [badCertificate] distinguishes a
     * certificate-trust rejection (untrusted chain, hostname mismatch, expiry, pin mismatch) from a
     * generic handshake failure — it maps to the [SocketException] TLS reason without any string-match.
     * [detail] carries the underlying diagnostic for logging.
     */
    data class TlsHandshake(
        val badCertificate: Boolean,
        val detail: String,
    ) : WebTransportFailure {
        override fun describe(): String =
            if (badCertificate) "WebTransport TLS certificate rejected: $detail" else "WebTransport TLS handshake failed: $detail"
    }

    /**
     * A transport/session error that isn't one of the specific cases above (connection dropped, session
     * error, an unclassified establishment failure). [detail] carries the underlying diagnostic. The
     * catch-all — deliberately last so the more specific variants are preferred when they can be told.
     */
    data class SessionError(
        val detail: String,
    ) : WebTransportFailure {
        override fun describe(): String = detail
    }
}
