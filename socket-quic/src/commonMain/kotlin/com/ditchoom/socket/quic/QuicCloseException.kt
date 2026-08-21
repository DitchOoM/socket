package com.ditchoom.socket.quic

import com.ditchoom.socket.SocketClosedException

/**
 * Thrown when a QUIC stream or connection operation fails because the connection (or stream) is gone.
 *
 * Extends [SocketClosedException] so it is caught uniformly alongside TCP/TLS connection-lost errors
 * (`catch (e: SocketClosedException)`, or `catch (e: IOException)` on JVM/Android), while additionally
 * carrying the structured [QuicCloseReason] — so callers recover the protocol-level cause without
 * parsing a message string. This keeps the thrown channel as type-faithful as the state channel
 * ([QuicConnectionState.Closed.reason]).
 *
 * ## Why the reason, and not a bare [QuicError]
 * This used to carry only a [QuicError], which cannot say **which side** closed the connection, nor
 * tell a graceful shutdown from a teardown the protocol never explained. The state channel has drawn
 * both distinctions since [QuicCloseReason] replaced its nullable error; the thrown channel had not,
 * and that asymmetry was a diagnostic dead end: a `PROTOCOL_VIOLATION` arriving ~485 ms after a path
 * migration (#437) is a different bug depending on whether *we* sent the offending frame or the peer
 * rejected ours, and the exception — the thing a device probe or a consumer's log actually has in hand
 * — could not say. The information was never missing; the driver resolves it from quiche's
 * `peer_error`/`local_error` and records it in [QuicConnectionState.Closed]. It just stopped here.
 *
 * [quicError] remains readable and unchanged for callers that only want the code.
 *
 * The two channels still differ in one way, deliberately: [attribution] names *which connection* this
 * came from, because a caught-and-logged exception is self-sufficient in a process holding several
 * connections — the gap that left reconnect-cycle incidents unattributable when only a reason was
 * recorded.
 */
class QuicCloseException(
    /**
     * Why the connection ended, exhaustively — including **which side** closed it and whether the
     * protocol explained the close at all. Match on it; do not infer the side from [quicError], which
     * cannot express it.
     */
    val closeReason: QuicCloseReason,
    message: String,
    cause: Throwable? = null,
    /**
     * Which connection this came from, as a value snapshot — see [QuicCloseAttribution].
     *
     * Defaults to [QuicCloseAttribution.Unattributed] so a throw site with no connection in hand stays
     * honest rather than inventing one; every driver-backed site supplies it.
     */
    val attribution: QuicCloseAttribution = QuicCloseAttribution.Unattributed,
) : SocketClosedException(withReason(message, closeReason, attribution), cause) {
    /**
     * The protocol error code this close carries, for callers that want the *why* without the *who* —
     * e.g. mapping to a WebTransport failure, which has no notion of sides.
     *
     * Derived from [closeReason] rather than stored, so the two can never disagree ([QuicCloseAttribution]
     * documents the same rule for identity). A close that names no error — [QuicCloseReason.Graceful],
     * and [QuicCloseReason.Unspecified] — answers [QuicError.NoError], exactly as this property did when
     * it was the only thing the exception carried. That fold is lossy on purpose and is why it is not
     * the thing to branch on: `Graceful` and `Unspecified` are indistinguishable here.
     */
    val quicError: QuicError get() = closeReason.errorOrNoError()

    /**
     * Compatibility constructor for the old `QuicCloseException(error, …)` shape, for throw sites that
     * genuinely hold only an error.
     *
     * The mapping mirrors the one [QuicConnectionState.Closed]'s deprecated constructor already
     * documents, because it is the same question: [QuicError.NoError] named no failure, so it becomes
     * [QuicCloseReason.Unspecified] — the honest "nothing explained this" — rather than
     * [QuicCloseReason.Graceful], which would claim a NO_ERROR close was exchanged when the caller
     * never observed one. Any other error becomes [QuicCloseReason.ByLocal]: a site raising an error it
     * computed itself is the local endpoint.
     */
    @Deprecated(
        "Construct with a QuicCloseReason: a bare QuicError cannot say which side closed the " +
            "connection, nor distinguish a graceful close from an unexplained teardown.",
        ReplaceWith("QuicCloseException(QuicCloseReason.ByLocal(quicError), message, cause, attribution)"),
        DeprecationLevel.WARNING,
    )
    constructor(
        quicError: QuicError,
        message: String,
        cause: Throwable? = null,
        attribution: QuicCloseAttribution = QuicCloseAttribution.Unattributed,
    ) : this(
        if (quicError is QuicError.NoError) QuicCloseReason.Unspecified else QuicCloseReason.ByLocal(quicError),
        message,
        cause,
        attribution,
    )

    private companion object {
        /**
         * Append the typed reason to the human [message] when it carries a real one, so every throw
         * site gets a helpful message ("…connection closed [peer: ProtocolViolation (0xa)]") without
         * duplicating formatting.
         *
         * The `peer:`/`local:` prefixes match what [the trace recorder][QuicConnectionState] already
         * writes for a `STATE … Closed` event, so a log line and a trace read the same way. This is a
         * *rendering* — [closeReason] stays the source of truth and nothing should parse it back.
         */
        fun withReason(
            message: String,
            reason: QuicCloseReason,
            attribution: QuicCloseAttribution,
        ): String {
            val described =
                when {
                    // A close that named no failure adds nothing: "[NoError]" would be noise on a
                    // message that already reads correctly. That covers Graceful and a side that
                    // closed with NO_ERROR alike.
                    reason is QuicCloseReason.Graceful -> message
                    reason !is QuicCloseReason.Unspecified && reason.errorOrNoError() is QuicError.NoError -> message
                    // Everything else renders through the one shared rendering — including
                    // Unspecified, which is not noise: it says no CONNECTION_CLOSE was exchanged and
                    // nothing timed out, the bit the old nullable error reported as a clean shutdown.
                    else -> "$message [${reason.describe()}]"
                }
            // Only the session id goes in the string: it is short, stable, and the one identifier that
            // makes a log line attributable. The rest stays typed on [attribution] rather than being
            // stuffed into a message nobody can parse back.
            return when (attribution) {
                QuicCloseAttribution.Unattributed -> described
                is QuicCloseAttribution.Attributed -> "$described (session=${attribution.identity.session})"
            }
        }
    }
}

/**
 * The [QuicError] to report for a reason that may name none — [QuicError.NoError] for
 * [QuicCloseReason.Graceful] and [QuicCloseReason.Unspecified].
 *
 * Lossy by construction, and confined to the thrown channel's [QuicCloseException.quicError] and the
 * few places that want an error code and nothing else. Anything deciding *how a connection ended*
 * should match on the reason.
 */
internal fun QuicCloseReason.errorOrNoError(): QuicError =
    when (this) {
        is QuicCloseReason.ByPeer -> error
        is QuicCloseReason.ByLocal -> error
        QuicCloseReason.Graceful, QuicCloseReason.Unspecified -> QuicError.NoError
    }
