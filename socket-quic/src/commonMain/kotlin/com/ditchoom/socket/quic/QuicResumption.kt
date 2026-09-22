package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed

/**
 * A session a server issued to this client in a TLS 1.3 NewSessionTicket (RFC 8446 §4.6.1), together
 * with the server's transport parameters (RFC 9000 §7.4.1) — everything a later connection needs to
 * resume the session and send 0-RTT.
 *
 * Opaque: the bytes are the QUIC engine's own serialization, meaningful only when offered back to the
 * same kind of engine. They include the session's resumption secret, so store them like a credential.
 *
 * Obtain one from [QuicScope.sessionTicket]; offer it with [QuicResumption.Offer].
 */
class QuicSessionTicket private constructor(
    private val bytes: ReadBuffer,
) {
    /** The serialized form, for storage: a read-only view positioned at its first byte. */
    fun serialized(): ReadBuffer = bytes.slice()

    override fun equals(other: Any?): Boolean = other is QuicSessionTicket && bytes.slice().contentEquals(other.bytes.slice())

    override fun hashCode(): Int {
        var result = 1
        for (i in 0 until bytes.limit()) result = 31 * result + bytes[i]
        return result
    }

    override fun toString(): String = "QuicSessionTicket(${bytes.limit()}B)"

    companion object {
        /**
         * A ticket from its [serialized] form. Copies the remaining bytes of [serialized] without
         * consuming them. Bytes that are not a ticket are not rejected here — the engine reports them
         * when they are offered, as [QuicFullHandshakeReason.TicketUnusable].
         */
        fun restore(serialized: ReadBuffer): QuicSessionTicket {
            val source = serialized.slice()
            val copy = BufferFactory.managed().allocate(source.remaining())
            copy.write(source)
            copy.resetForRead()
            return QuicSessionTicket(copy)
        }
    }
}

/** Whether a server has issued this client connection a [QuicSessionTicket]. */
sealed interface QuicSessionTicketState {
    /** No ticket has arrived: the server has not sent one yet, or this is a server-accepted connection. */
    data object NotIssued : QuicSessionTicketState

    /** The newest ticket the server has sent on this connection. */
    data class Issued(
        val ticket: QuicSessionTicket,
    ) : QuicSessionTicketState
}

/**
 * Which session a client connection offers the server. See [QuicOptions.resumption].
 *
 * A reconnect — after a path is lost for good, or any network change the connection cannot migrate
 * across — offers the ticket the previous connection received:
 *
 * ```kotlin
 * val resumption =
 *     when (val last = previous.sessionTicket.value) {
 *         QuicSessionTicketState.NotIssued -> QuicResumption.None
 *         is QuicSessionTicketState.Issued -> QuicResumption.Offer(last.ticket, QuicEarlyData.Replayable { hello() })
 *     }
 * withQuicConnection(host, port, options.copy(resumption = resumption)) { … }
 * ```
 */
sealed interface QuicResumption {
    /** Offer nothing: a full handshake. */
    data object None : QuicResumption

    /**
     * Offer [ticket] for resumption (RFC 8446 §2.2), and send [earlyData] as 0-RTT if the session
     * permits it. The handshake's answer is [QuicScope.resumption].
     */
    class Offer(
        val ticket: QuicSessionTicket,
        val earlyData: QuicEarlyData = QuicEarlyData.None,
    ) : QuicResumption
}

/**
 * What a resuming client sends before the handshake completes (0-RTT, RFC 9001 §4.6).
 *
 * ## ⚠️ 0-RTT data can be replayed
 * An attacker who captures a client's first flight can deliver it to the server again, and the server
 * cannot tell (RFC 8446 §8, RFC 9001 §9.2). Nothing is sent as 0-RTT unless the caller hands it over
 * here, in [Replayable]'s block — and everything written there must be safe for the server to act on
 * more than once.
 */
sealed interface QuicEarlyData {
    /** Send nothing before the handshake completes. Resuming still skips the certificate exchange. */
    data object None : QuicEarlyData

    /**
     * Run [write] before the client's first flight leaves, so what it writes travels in that flight as
     * 0-RTT when the session permits it.
     *
     * [write] runs exactly once, before [withQuicConnection]'s block. Its writes are queued, not sent,
     * until it returns — so it must not wait on the peer; a read, or a write the session's remembered
     * flow control cannot take whole, releases the first flight at that point.
     *
     * If the server does not accept 0-RTT, the bytes are sent again once the handshake completes, so
     * they reach the server exactly once either way; [QuicScope.resumption] says which happened. The
     * engine re-sends them rather than resetting the streams as RFC 9001 §4.6.2 has a client do, so they
     * are read under whatever application protocol the new handshake negotiated: offer early data with
     * a single ALPN, or write bytes that are valid under every protocol offered.
     *
     * The streams [write] opens are ordinary streams of the connection; to keep one for the main block,
     * hand it out — for example through a `CompletableDeferred` completed inside [write].
     */
    class Replayable(
        val write: suspend QuicEarlyDataScope.() -> Unit,
    ) : QuicEarlyData
}

/** What a [QuicEarlyData.Replayable] block can do: open streams and write to them. */
interface QuicEarlyDataScope {
    /** The connection's [QuicScope.bufferFactory]. */
    val bufferFactory: BufferFactory

    /** Open a bidirectional stream; what is written to it here may travel as 0-RTT. */
    suspend fun openStream(): QuicByteStream

    /** Open a unidirectional stream; what is written to it here may travel as 0-RTT. */
    suspend fun openUniStream(): QuicByteStream
}

/**
 * How a connection's handshake used a session ticket — read from [QuicScope.resumption] on either end.
 * Whether the session was resumed is the one fact both ends always know; the rest is what TLS reports
 * from this endpoint's side.
 */
sealed interface QuicResumptionOutcome {
    /** The server resumed the session (RFC 8446 §2.2): no certificate was exchanged. [earlyData] is 0-RTT's fate. */
    data class Resumed(
        val earlyData: QuicEarlyDataOutcome,
    ) : QuicResumptionOutcome

    /** The handshake was full, for [reason]. Any 0-RTT data was discarded by the server and sent again afterwards. */
    data class FullHandshake(
        val reason: QuicFullHandshakeReason,
    ) : QuicResumptionOutcome
}

/** Why a handshake did not resume a session, as far as this endpoint can tell. */
sealed interface QuicFullHandshakeReason {
    /** No session ticket was offered. */
    data object NoTicketOffered : QuicFullHandshakeReason

    /**
     * The client passed a ticket the engine could not load — [quicheError] is its error code — so no
     * session was offered. Discard the stored ticket. Reported by the client only.
     */
    data class TicketUnusable(
        val quicheError: Int,
    ) : QuicFullHandshakeReason

    /**
     * A ticket was offered and the server could not resume it: expired, encrypted under a key the server
     * does not hold (see [QuicSessionTicketKeys]), or issued by a different server.
     */
    data object TicketNotResumed : QuicFullHandshakeReason

    /**
     * Not reported: on a server with 0-RTT disabled, TLS reports only that, not whether the client
     * offered a ticket. Enable [QuicOptions.enableEarlyData] on the server to tell the two apart.
     */
    data object NotReported : QuicFullHandshakeReason
}

/** What happened to 0-RTT on a resumed connection. */
sealed interface QuicEarlyDataOutcome {
    /** The server accepted 0-RTT: what the client wrote as early data was processed before the handshake completed. */
    data object Accepted : QuicEarlyDataOutcome

    /** 0-RTT was not accepted, for [reason]. Early data, if any was written, was sent after the handshake. */
    data class NotAccepted(
        val reason: QuicEarlyDataRejection,
    ) : QuicEarlyDataOutcome
}

/**
 * Why 0-RTT was not accepted — TLS's own account (BoringSSL's `ssl_early_data_reason_t`), from this
 * endpoint's side of the handshake.
 */
sealed interface QuicEarlyDataRejection {
    /** This endpoint did not enable 0-RTT: a client that offered [QuicEarlyData.None], or a server without [QuicOptions.enableEarlyData]. */
    data object NotEnabled : QuicEarlyDataRejection

    /** The peer did not take part: a server that declined the client's 0-RTT, or a client that offered none. */
    data object DeclinedByPeer : QuicEarlyDataRejection

    /** The ticket does not permit 0-RTT: its server did not accept early data when it issued it. */
    data object NotPermittedBySession : QuicEarlyDataRejection

    /** The server sent a HelloRetryRequest, which always rejects 0-RTT (RFC 8446 §4.1.4). */
    data object HelloRetryRequest : QuicEarlyDataRejection

    /** The application protocol is not the one the session was established with. */
    data object AlpnMismatch : QuicEarlyDataRejection

    /** The ticket's age as the client reports it disagrees with the server's clock (RFC 8446 §8.3). */
    data object TicketAgeSkew : QuicEarlyDataRejection

    /** The server's transport parameters changed since the session was issued (RFC 9000 §7.4.1). */
    data object TransportParametersChanged : QuicEarlyDataRejection

    /** A reason this library does not model; [code] is TLS's value for it. */
    data class Unrecognized(
        val code: Int,
    ) : QuicEarlyDataRejection
}
