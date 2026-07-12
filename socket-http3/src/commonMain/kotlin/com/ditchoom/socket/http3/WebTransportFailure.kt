package com.ditchoom.socket.http3

/**
 * A typed WebTransport-over-HTTP/3 (RFC 9220 + draft-ietf-webtrans-http3) failure — the reason a
 * [WebTransportException] is raised. Every distinct failure mode is its own variant carrying its
 * structured context (the CONNECT reject [ConnectRejected.status], the target authority/path, …) rather
 * than a hand-written message string; [describe] renders the human-readable diagnostic from the typed
 * fields (and IS the exception message, so the wire-visible text is unchanged). Errors stay typed, never
 * stringly — mirroring the [Http3Violation] / `QuicError` sealed convention so a consumer reasons about
 * the failure by **exhaustive `when`**, not by matching the message text. The `when` being `else`-free is
 * the regression guard: a new variant added here won't compile at the consumers until its case is chosen.
 */
sealed interface WebTransportFailure {
    /** A human-readable diagnostic built from this failure's typed fields (used as the exception message). */
    fun describe(): String

    /**
     * WebTransport is not enabled on this connection — it was bootstrapped without a
     * [WebTransportOptions], so no Extended CONNECT / HTTP Datagrams were advertised locally. A local
     * misconfiguration, not a network/peer failure.
     */
    data object NotEnabledLocally : WebTransportFailure {
        override fun describe(): String =
            "WebTransport is not enabled on this connection — pass WebTransportOptions to withHttp3Connection()/bootstrap()"
    }

    /**
     * The peer did not advertise WebTransport support (Extended CONNECT + H3_DATAGRAM + sessions) in its
     * HTTP/3 SETTINGS, so no session could be opened. A structural capability gap on the far end.
     */
    data object PeerDoesNotSupport : WebTransportFailure {
        override fun describe(): String = "the peer did not advertise WebTransport support (Extended CONNECT + H3_DATAGRAM + sessions)"
    }

    /**
     * The server rejected the Extended CONNECT with a non-2xx HTTP [status] (RFC 9220). [authority] and
     * [path] are the CONNECT target. The [status] is FIRST-CLASS here: a consumer distinguishes e.g. a
     * `401` auth rejection from a `404` unknown path WITHOUT parsing the message — the whole point of the
     * typed model.
     */
    data class ConnectRejected(
        val status: Int,
        val authority: String,
        val path: String,
    ) : WebTransportFailure {
        override fun describe(): String = "WebTransport CONNECT to $authority$path was rejected with status $status"
    }

    /**
     * A datagram was sent but the underlying QUIC connection has no datagram support enabled
     * (draft-ietf-webtrans-http3 §4.4). The underlying transport error is preserved as the exception's
     * `cause`, not folded into the message string.
     */
    data object DatagramsNotEnabled : WebTransportFailure {
        override fun describe(): String = "QUIC datagrams are not enabled on this connection"
    }
}
