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
     * protocol violation), or the connection idled out, in which case [error] is
     * [QuicError.IdleTimeout].
     *
     * An idle timeout is deliberately modelled here rather than as its own top-level case: it is a
     * local decision with no wire code, and [QuicError.IdleTimeout] already names it, so giving it a
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
     */
    data object Unspecified : QuicCloseReason
}
