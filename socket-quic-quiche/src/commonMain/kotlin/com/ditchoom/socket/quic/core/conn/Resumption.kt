package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicEarlyDataReason
import com.ditchoom.socket.quic.QuicFullHandshakeReason
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.SessionOffer
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.udp.NativeBytes

/** When a client's first flight may leave. */
internal enum class FirstFlight {
    /** As soon as the connection is served. */
    Immediate,

    /** Once the caller's 0-RTT block returned, or one of its operations waits on the peer. */
    HeldForEarlyData,
}

/** What a client offered quiche before its first flight. */
internal sealed interface Offer {
    /** No ticket. */
    data object NoTicket : Offer

    /** quiche refused the ticket with [quicheError], so none was offered. */
    class Unusable(
        val quicheError: Int,
    ) : Offer

    /** quiche took the ticket. */
    class Offered(
        val flight: FirstFlight,
    ) : Offer
}

/**
 * Offers the serialized session at [bytes], [length] long, on a client connection quiche has created and not yet sent
 * or received on: the only moment `quiche_conn_set_session` is valid. [flight] is when the first flight may leave if
 * quiche takes it.
 */
internal fun offerSession(
    q: QuicheConnRef,
    bytes: NativeBytes,
    length: Int,
    flight: FirstFlight,
): Offer {
    val rc = q.api.connSetSession(q.handle, bytes.address, length)
    return if (rc < 0) Offer.Unusable(rc) else Offer.Offered(flight)
}

/** What a client's [ClientSession] says of its first flight. */
internal enum class FlightGate { Open, Held }

/** Whether a client's first session ticket has been handed up, or was too large for its scratch to take. */
private enum class TicketSeen { NotYet, Taken }

/**
 * A client connection's resumption: what it offered, whether its first flight is held for 0-RTT, and the first session
 * ticket its server issues after the handshake, copied into [scratch] and handed up once. A session larger than the
 * scratch is not kept: the scratch bounds what a ticket may hold.
 */
@Confined
internal class ClientSession(
    private val q: QuicheConnRef,
    private val offer: Offer,
    private val scratch: NativeBytes,
) {
    private var gate =
        when (offer) {
            is Offer.Offered ->
                when (offer.flight) {
                    FirstFlight.Immediate -> FlightGate.Open
                    FirstFlight.HeldForEarlyData -> FlightGate.Held
                }
            is Offer.NoTicket, is Offer.Unusable -> FlightGate.Open
        }
    private var seen = TicketSeen.NotYet

    fun gate(): FlightGate = gate

    /** The first flight may leave in the next write. */
    fun release() {
        gate = FlightGate.Open
    }

    /** How the handshake used what was offered; read once, at establishment. */
    fun outcome(): QuicResumptionOutcome =
        if (q.api.connIsResumed(q.handle)) {
            QuicResumptionOutcome.Resumed(QuicEarlyDataReason.earlyData(q.api.connEarlyDataReason(q.handle)))
        } else {
            QuicResumptionOutcome.FullHandshake(
                when (offer) {
                    is Offer.NoTicket -> QuicFullHandshakeReason.NoTicketOffered
                    is Offer.Unusable -> QuicFullHandshakeReason.TicketUnusable(offer.quicheError)
                    is Offer.Offered -> QuicFullHandshakeReason.TicketNotResumed
                },
            )
        }

    /** Established: the first session ticket quiche holds goes to [sink], once. One length read per serve until then. */
    fun takeTicket(sink: ConnSink) {
        if (seen == TicketSeen.Taken) return
        val length = q.api.connSession(q.handle, 0L, 0)
        if (length <= 0) return
        seen = TicketSeen.Taken
        if (length > scratch.capacity) return
        if (q.api.connSession(q.handle, scratch.address, length) == length) sink.ticket(scratch, length)
    }

    companion object {
        /** A serialized session with the server's certificate chain fits; one that does not is not kept. */
        const val TICKET_BYTES = 16 * 1024
    }
}

/** What a server connection knows of the client's 0-RTT this serve. */
internal enum class EarlyStep {
    /** Not in early data. */
    None,

    /** quiche reports the client's 0-RTT accepted, for the first time. */
    JustAccepted,

    /** Accepted before; the handshake has not completed. */
    Accepted,
}

private enum class Accepting { Never, NotYet, Accepted }

/** A server connection's resumption: whether quiche accepted the client's 0-RTT, and how the handshake used its ticket. */
@Confined
internal class ServerSession(
    private val q: QuicheConnRef,
    rule: EarlyDataRule,
) {
    private var accepting =
        when (rule) {
            EarlyDataRule.Refused -> Accepting.Never
            EarlyDataRule.Accepted -> Accepting.NotYet
        }

    /** While the handshake is in progress. */
    fun step(): EarlyStep =
        when (accepting) {
            Accepting.Never -> EarlyStep.None
            Accepting.Accepted -> EarlyStep.Accepted
            Accepting.NotYet ->
                if (q.api.connIsInEarlyData(q.handle)) {
                    accepting = Accepting.Accepted
                    EarlyStep.JustAccepted
                } else {
                    EarlyStep.None
                }
        }

    /** Read at establishment, or when 0-RTT is accepted, which settles it. */
    fun outcome(): QuicResumptionOutcome =
        QuicEarlyDataReason.outcome(QuicRole.Server, SessionOffer.None, q.api.connIsResumed(q.handle), q.api.connEarlyDataReason(q.handle))
}
