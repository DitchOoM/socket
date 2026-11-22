package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicEarlyDataOutcome
import com.ditchoom.socket.quic.QuicEarlyDataReason
import com.ditchoom.socket.quic.QuicFullHandshakeReason
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.SessionOffer
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.Pooled
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlinx.coroutines.Runnable

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

/**
 * What a client took from its stores and offered (a session ticket, a NEW_TOKEN token, or both), by whether it may have
 * been sent: privacy asks only that neither goes out twice (RFC 8446 §C.4, RFC 9000 §8.1.3), so what never left goes back.
 */
internal sealed interface KeptTicket {
    /** The connection offered nothing from a store: the caller's own ticket, or nothing. */
    data object None : KeptTicket

    /** No datagram of the connection has been written yet; [back] hands what was taken back to its stores. */
    class Unsent(
        val back: Runnable,
    ) : KeptTicket

    /** A datagram was written, so what was taken may be on the wire: it stays used. */
    data object Sent : KeptTicket

    /** Handed back to its store. */
    data object Returned : KeptTicket
}

/**
 * Whether a client holds its output until its server is checked: from the completion of a handshake that did not accept
 * the 0-RTT it sent, which quiche sends again in 1-RTT to a server whose certificate is still to be checked.
 */
private enum class CheckHold { None, UntilChecked }

/** Whether a client's first session ticket (or NEW_TOKEN token) was taken, or was too large for its scratch. */
private enum class TicketSeen { NotYet, Taken }

/**
 * Memory a client connection copies its server's session ticket or NEW_TOKEN token into, lent by its loop for that one
 * copy: the one who keeps it copies again, so the loop's connections share a few.
 */
internal class SessionScratch(
    index: Int,
    val bytes: NativeBytes,
) : Pooled(index)

/**
 * A client connection's resumption: what it offered, whether its first flight is held for 0-RTT, and the first session
 * ticket its server issues after the handshake, copied into a [scratch] slot and handed up once; likewise the first
 * NEW_TOKEN token, kept in [tokens]. A session larger than [ClientSession.TICKET_BYTES] is not kept. With every slot
 * lent at once, the copy waits for the next serve. A client whose server is checked after the handshake ([chain]) holds
 * its output from the completion until the check passed, when the handshake did not accept its 0-RTT.
 */
@Confined
internal class ClientSession(
    private val q: QuicheConnRef,
    private val offer: Offer,
    private val scratch: FixedPool<SessionScratch>,
    private var kept: KeptTicket,
    private val tokens: TokenHome,
    private val chain: ChainSource,
) {
    private var hold = CheckHold.None

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
    private var tokenSeen =
        when (tokens) {
            is TokenHome.NotKept -> TicketSeen.Taken
            is TokenHome.Memory -> TicketSeen.NotYet
        }

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

    /** Once, when the handshake completed: whether output waits for the server's check, as [ClientSession] says. */
    fun completed() {
        val unaccepted = outcome() != QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.Accepted)
        hold =
            when (chain) {
                is ChainSource.NotAsked -> CheckHold.None
                is ChainSource.Copied -> if (sentEarlyData() && unaccepted) CheckHold.UntilChecked else CheckHold.None
            }
    }

    /** Whether output may leave now: held until the check passed, unless the connection is closing. */
    fun checkGate(life: Life): FlightGate =
        when (hold) {
            CheckHold.None -> FlightGate.Open
            CheckHold.UntilChecked -> if (life is Life.Running) FlightGate.Held else FlightGate.Open
        }

    /** The server passed its check. */
    fun checked() {
        hold = CheckHold.None
    }

    private fun sentEarlyData(): Boolean =
        when (offer) {
            is Offer.Offered -> offer.flight == FirstFlight.HeldForEarlyData
            is Offer.NoTicket, is Offer.Unusable -> false
        }

    /** The connection ended having written [out]: a kept ticket no datagram carried goes back to its store. */
    fun ended(out: FirstOut) {
        when (val now = kept) {
            is KeptTicket.Unsent ->
                when (out) {
                    FirstOut.NotYet -> {
                        kept = KeptTicket.Returned
                        now.back.run()
                    }
                    FirstOut.Written -> kept = KeptTicket.Sent
                }
            is KeptTicket.None, is KeptTicket.Sent, is KeptTicket.Returned -> Unit
        }
    }

    /** Established: the first session ticket quiche holds goes to [sink], once. One length read per serve until then. */
    fun takeTicket(sink: ConnSink) {
        if (seen == TicketSeen.Taken) return
        val length = q.api.connSession(q.handle, 0L, 0)
        if (length <= 0) return
        if (length > TICKET_BYTES) {
            seen = TicketSeen.Taken
            return
        }
        scratch.take({ slot ->
            try {
                seen = TicketSeen.Taken
                if (q.api.connSession(q.handle, slot.bytes.address, length) == length) sink.ticket(slot.bytes, length)
            } finally {
                scratch.give(slot)
            }
        }, { Unit })
    }

    /** Established: the first NEW_TOKEN token the server sends is kept for the next connection, once. */
    fun takeToken() {
        if (tokenSeen == TicketSeen.Taken) return
        scratch.take({ slot ->
            try {
                val length = q.api.connTakeNewToken(q.handle, slot.bytes.address, TICKET_BYTES)
                if (length > 0) {
                    tokenSeen = TicketSeen.Taken
                    tokens.keep(slot.bytes, length)
                }
            } finally {
                scratch.give(slot)
            }
        }, { Unit })
    }

    companion object {
        /** A serialized session with the server's certificate chain fits; one that does not is not kept. */
        const val TICKET_BYTES = 16 * 1024

        /** Scratch slots per loop: connections of one loop copying a ticket or token at the same moment. */
        const val SCRATCH_SLOTS = 2
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
