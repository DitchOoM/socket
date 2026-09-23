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
 * [protocol] travels with them because it decides what the bytes *mean*: a session speaks one
 * application protocol, and it is the ticket — never a caller-supplied string — that names the
 * protocol a [QuicResumption.ResumeWithEarlyData] connection offers.
 *
 * Obtain one from [QuicScope.sessionTicket]; offer it with [QuicResumption.Resume] or
 * [QuicResumption.ResumeWithEarlyData].
 */
class QuicSessionTicket private constructor(
    /** The application protocol (RFC 7301 ALPN) this session speaks. */
    val protocol: String,
    private val bytes: ReadBuffer,
) {
    /** The serialized form, for storage: a read-only view positioned at its first byte. */
    fun serialized(): ReadBuffer = bytes.slice()

    override fun equals(other: Any?): Boolean =
        other is QuicSessionTicket && protocol == other.protocol && bytes.slice().contentEquals(other.bytes.slice())

    override fun hashCode(): Int {
        var result = protocol.hashCode()
        for (i in 0 until bytes.limit()) result = 31 * result + bytes[i]
        return result
    }

    override fun toString(): String = "QuicSessionTicket($protocol, ${bytes.limit()}B)"

    companion object {
        /**
         * A ticket from its [serialized] form and the [protocol] it was stored with — store the two
         * together, since offering a session says which protocol the connection speaks. Copies the
         * remaining bytes of [serialized] without consuming them. Bytes that are not a ticket are not
         * rejected here — the engine reports them when they are offered, as
         * [QuicFullHandshakeReason.TicketUnusable].
         */
        fun restore(
            serialized: ReadBuffer,
            protocol: String,
        ): QuicSessionTicket {
            val source = serialized.slice()
            val copy = BufferFactory.managed().allocate(source.remaining())
            copy.write(source)
            copy.resetForRead()
            return QuicSessionTicket(protocol, copy)
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
 *         is QuicSessionTicketState.Issued -> QuicResumption.ResumeWithEarlyData(last.ticket) { hello() }
 *     }
 * withQuicConnection(host, port, options.copy(resumption = resumption)) { … }
 * ```
 */
sealed interface QuicResumption {
    /** Offer nothing: a full handshake. */
    data object None : QuicResumption

    /**
     * Offer [ticket] for resumption (RFC 8446 §2.2) and send nothing before the handshake completes.
     * Resuming still skips the certificate exchange. The connection offers [QuicOptions.alpnProtocols],
     * as any other connection does — nothing is written before the protocol is negotiated.
     */
    class Resume(
        val ticket: QuicSessionTicket,
    ) : QuicResumption

    /**
     * Offer [ticket] and run [write] before the client's first flight leaves, so what it writes travels
     * in that flight as 0-RTT when the session permits it.
     *
     * ## ⚠️ 0-RTT data can be replayed
     * An attacker who captures a client's first flight can deliver it to the server again, and the
     * server cannot tell (RFC 8446 §8, RFC 9001 §9.2). Nothing is sent as 0-RTT unless it is written
     * here, and everything written here must be safe for the server to act on more than once.
     *
     * ## One protocol, by construction
     * A connection built from this offers exactly one application protocol —
     * [QuicSessionTicket.protocol], the one the session speaks — instead of
     * [QuicOptions.alpnProtocols]. There is no protocol to pass and no list to widen, so early bytes
     * cannot be read under a protocol other than the one they were written for: not when the server
     * accepts 0-RTT, and not when it declines and the engine sends them again after the handshake
     * (which is what it does instead of resetting the streams as RFC 9001 §4.6.2 has a client do).
     * [QuicOptions.alpnProtocols] must still list that protocol — it is what the caller says this
     * connection may speak — or the connect fails with [EarlyDataProtocolNotOfferedException] before
     * anything is sent.
     *
     * [write] runs exactly once, before [withQuicConnection]'s block. Its writes are queued, not sent,
     * until it returns — so it must not wait on the peer; a read, or a write the session's remembered
     * flow control cannot take whole, releases the first flight at that point. If the server does not
     * accept 0-RTT the bytes are sent again once the handshake completes, so they reach the server
     * exactly once either way; [QuicScope.resumption] says which happened.
     *
     * The streams [write] opens are ordinary streams of the connection; to keep one for the main block,
     * hand it out — for example through a `CompletableDeferred` completed inside [write].
     */
    class ResumeWithEarlyData(
        val ticket: QuicSessionTicket,
        val write: suspend QuicEarlyDataScope.() -> Unit,
    ) : QuicResumption
}

/**
 * A [QuicResumption.ResumeWithEarlyData] connection offers exactly [sessionProtocol], the protocol its
 * session speaks, and [offered] — the caller's [QuicOptions.alpnProtocols] — does not list it. Raised
 * before the connection opens a socket: the two are known as soon as connect has both, and narrowing to
 * a protocol the caller never offered would speak a protocol it did not ask for.
 */
class EarlyDataProtocolNotOfferedException(
    val sessionProtocol: String,
    val offered: List<String>,
) : IllegalArgumentException(
        "0-RTT resumes a session speaking '$sessionProtocol', which this connection's application protocols $offered do not include",
    )

/** What a [QuicResumption.ResumeWithEarlyData] block can do: open streams and write to them. */
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
    /**
     * This endpoint did not enable 0-RTT: a client that offered [QuicResumption.Resume], or a server
     * without [QuicOptions.enableEarlyData].
     */
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
