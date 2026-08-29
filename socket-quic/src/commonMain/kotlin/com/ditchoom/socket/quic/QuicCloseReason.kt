package com.ditchoom.socket.quic

/**
 * Why a QUIC connection reached [QuicConnectionState.Closed] — as a value, never as an absence.
 *
 * ## What this replaces, and why
 * The terminal state used to carry `error: QuicError?`, where `null` meant "clean shutdown". That one
 * nullable was doing two unrelated jobs: it meant *"both peers finished politely"* and also *"we have
 * no idea why this ended"*, because a connection torn down by scope cancellation or by an exception
 * unwinding the driver loop reports no CONNECTION_CLOSE frame and no timeout — so it produced `null`
 * too, and read as graceful.
 *
 * That is not hypothetical. `QuicheDriver`'s own teardown comment records an incident where exactly
 * this collapse made an API-35 emulator failure undiagnosable: every caller resolved its reason
 * through the `NoError` fallback and got the opaque `QuicCloseException: connection closed`. Encoding
 * "I don't know" as "everything was fine" costs you the one bit you needed at the moment you needed it.
 *
 * So [Unspecified] exists to say the true thing. A close cannot present as [Graceful] unless a
 * CONNECTION_CLOSE carrying NO_ERROR was actually exchanged.
 *
 * ## The peer/local split
 * The old shape also discarded *which side* closed the connection: `resolveCloseError` preferred the
 * peer's error over our own and then returned a bare [QuicError], so a server rejecting our transport
 * parameters and quiche aborting locally on a TLS failure arrived identical. [ByPeer] and [ByLocal]
 * keep that distinction, which is usually the first question worth asking.
 *
 * Exhaustive `when` over these four is the intended way to read a close; there is no boolean to
 * consult and nothing to null-check.
 */
sealed interface QuicCloseReason {
    /**
     * Both ends finished cleanly: a CONNECTION_CLOSE carrying `NO_ERROR` (RFC 9000 §20.1 code 0x0) was
     * exchanged. The only reason that means "nothing went wrong".
     */
    data object Graceful : QuicCloseReason

    /**
     * The **peer** sent a CONNECTION_CLOSE carrying [error] — the remote tore us down (a strict server
     * rejecting our streams or transport parameters, an application-level close code, and so on).
     */
    data class ByPeer(
        val error: QuicError,
    ) : QuicCloseReason

    /**
     * **We** closed the connection with [error] — quiche aborted locally (handshake/TLS failure,
     * protocol violation), the connection idled out ([QuicError.IdleTimeout]), or the caller's
     * establishment bound elapsed with the handshake still in flight ([QuicError.HandshakeTimeout]).
     *
     * Those two timeouts are deliberately modelled here rather than as their own top-level cases: each
     * is a local decision with no wire code, and its [QuicError] already names it, so giving it a
     * second name would put the same fact in two places.
     */
    data class ByLocal(
        val error: QuicError,
    ) : QuicCloseReason

    /**
     * The connection ended without any CONNECTION_CLOSE being exchanged and without timing out — the
     * driver's scope was cancelled, or an exception unwound its loop.
     *
     * This is an honest "unknown", not a failure verdict, and it is the case the old nullable silently
     * folded into "clean". If you are seeing it where you expected [Graceful], the connection did not
     * shut down through the protocol.
     *
     * ## Where a stateless reset lands
     * RFC 9000 §10 lists three ways a connection ends and this hierarchy names two of them —
     * [ByPeer]/[ByLocal] cover immediate close, [ByLocal] with [QuicError.IdleTimeout] covers the idle
     * timeout. The third, **stateless reset**, has no case here because quiche's C API cannot report it:
     * the vendored header exposes `quiche_config_set_stateless_reset_token` and the `reset_token`
     * argument to `quiche_conn_new_scid`, but nothing that says *this connection was terminated by a
     * reset we received*. Every other `reset` symbol in that header is stream-scoped
     * (`QUICHE_ERR_STREAM_RESET`, `reset_stream_count_*`), which is a different event entirely.
     *
     * So a received stateless reset arrives here: no CONNECTION_CLOSE was exchanged and nothing timed
     * out. Adding a `StatelessReset` case would be modelling a state the backend has no way to produce —
     * a variant no code could ever construct, which is worse than absent, because it reads as a
     * distinction the library can draw. If quiche later exposes a connection-level accessor, this is the
     * case that should split.
     */
    data object Unspecified : QuicCloseReason
}

/**
 * A short human rendering of a close reason — the *side* plus the error's own [QuicError.describe],
 * e.g. `peer: ProtocolViolation (0xa)`.
 *
 * One rendering, in one place, because this string is what ends up in a log line, an HTTP/3 loopback
 * diagnostic, and a device probe's output — and three copies of "how a close is spelled" would drift
 * apart exactly when someone is correlating them. The `peer:`/`local:` prefixes match what the QUIC
 * trace recorder writes for a `STATE … Closed` event, so a log and a trace read the same way.
 *
 * It is a rendering, never a parse target: the reason itself is the exhaustive type to match on.
 */
fun QuicCloseReason.describe(): String =
    when (this) {
        QuicCloseReason.Graceful -> "graceful"
        QuicCloseReason.Unspecified -> "unexplained"
        is QuicCloseReason.ByPeer -> "peer: ${error.describe()}"
        is QuicCloseReason.ByLocal -> "local: ${error.describe()}"
    }
