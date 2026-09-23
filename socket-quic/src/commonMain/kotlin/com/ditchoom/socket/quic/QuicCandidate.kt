package com.ditchoom.socket.quic

import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress

/**
 * One place a QUIC handshake can be sent: an IP literal and a UDP port, never a name.
 *
 * The port is part of the candidate rather than of the connect because a peer's candidates do not
 * share one. A name resolves to several addresses on one port; a peer publishes its global address on
 * the port it bound and the server-reflexive pair a NAT assigned it, which is a different port, and a
 * type that could not say so would make the peer-to-peer case unrepresentable.
 */
data class QuicEndpoint(
    val address: ResolvedAddress,
    val port: Int,
) {
    override fun toString(): String =
        when (address.family) {
            IpFamily.V4 -> "${address.ip}:$port"
            IpFamily.V6 -> "[${address.ip}]:$port"
        }
}

/**
 * Who a QUIC connect is dialling: the endpoints to try, and the one identity every one of them is
 * expected to present.
 *
 * The split is the whole point. quiche needs a literal endpoint to send to and a server name to put in
 * SNI and verify the certificate against, and those are the same string only in the simplest case. A
 * candidate list means many endpoints and one identity, so a connect that reused the endpoint as the
 * name would put a literal into SNI and fail verification against every certificate ever issued for
 * the peer.
 */
sealed interface QuicPeer {
    /** The name to present, and to verify the peer's certificate against. */
    val serverName: String

    /**
     * A name and the port every address it resolves to is dialled on. The addresses come from the
     * connect's [NameResolution][com.ditchoom.socket.NameResolution] and are raced in RFC 8305 §4
     * order, so a blackholed AAAA costs one attempt delay instead of a connect timeout.
     */
    data class Named(
        val hostname: String,
        val port: Int,
    ) : QuicPeer {
        override val serverName: String get() = hostname
    }

    /**
     * An explicit candidate set for one identity — the peer-to-peer shape. Both peers publish their
     * candidates through a signalling server, both send to every candidate of the other so each NAT
     * sees an outbound packet first, and the handshake races across them; pair this with
     * [ConnectPacing.Simultaneous][com.ditchoom.socket.ConnectPacing.Simultaneous] so the first flights
     * leave together rather than one attempt delay apart.
     *
     * [serverName] is the identity, not a route: a peer authenticated by
     * [QuicOptions.serverCertificateHashes] is not authenticated by its name, and the name it presents
     * still has to be the same at every candidate or the handshake that wins would be a handshake with
     * somebody else.
     *
     * [endpoints] are dialled in the order given — the caller's own preference (a global IPv6 address
     * before a server-reflexive IPv4 pair, say), not a re-derived one, because only the caller knows
     * how its candidates were gathered.
     */
    data class Candidates(
        val endpoints: List<QuicEndpoint>,
        override val serverName: String,
    ) : QuicPeer {
        init {
            require(endpoints.isNotEmpty()) { "a connect needs at least one candidate endpoint" }
        }
    }
}

/**
 * Which endpoint a connection was dialled at, and what became of the candidates that lost.
 *
 * Handed to the block of the [QuicPeer] form of `withQuicConnection`: for a peer-to-peer connect the
 * winning candidate *is* the result — it is the pair that was nominated, and the one a caller reports
 * back to its signalling server.
 */
sealed interface QuicCandidateRace {
    /** One candidate, so nothing to race: it was dialled and it answered. */
    data class Unopposed(
        val endpoint: QuicEndpoint,
    ) : QuicCandidateRace

    /** [winner] completed its handshake first; every other candidate ended as [lost] says. */
    data class Raced(
        val winner: QuicEndpoint,
        val lost: List<QuicCandidateLoss>,
    ) : QuicCandidateRace {
        init {
            require(lost.isNotEmpty()) { "a race has at least one losing candidate; one candidate is Unopposed" }
        }
    }
}

/**
 * Why one candidate is not the connection. Exhaustive over what can actually happen to an attempt: it
 * never ran, it failed, it was still running when another won, or it finished too late and was closed.
 * There is no fifth thing, and in particular there is no state in which an attempt that opened a socket
 * is still holding one.
 */
sealed interface QuicCandidateLoss {
    val endpoint: QuicEndpoint

    /** The pacer never reached it: the race was decided while it was still waiting its turn. */
    data class NeverAttempted(
        override val endpoint: QuicEndpoint,
    ) : QuicCandidateLoss

    /** Its handshake failed on its own, and the failure said nothing that would stop the others. */
    data class HandshakeFailed(
        override val endpoint: QuicEndpoint,
        val error: Throwable,
    ) : QuicCandidateLoss

    /** Still handshaking when another candidate won; cancelled, and everything it had acquired released. */
    data class Abandoned(
        override val endpoint: QuicEndpoint,
    ) : QuicCandidateLoss

    /**
     * Its handshake completed after the race was decided, so it is a second live connection to the same
     * peer: closed with CONNECTION_CLOSE, its socket released and its quiche connection freed.
     */
    data class ClosedAsLate(
        override val endpoint: QuicEndpoint,
    ) : QuicCandidateLoss
}
