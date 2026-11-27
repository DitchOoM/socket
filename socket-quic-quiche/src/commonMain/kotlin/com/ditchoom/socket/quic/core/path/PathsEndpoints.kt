package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.life.PeerAddress
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.decodePathKey

/**
 * One local endpoint of a client: its address as quiche reads it ([key]), the receive info for datagrams that arrive
 * on it, and the loop's socket that holds it ([socket]), which datagrams quiche writes from here leave by ([out]).
 * [made] says whose its sockaddr and receive info are, and so who frees them; [prior] is the round trip quiche assumes
 * for the path from here until it has a sample.
 */
internal class LocalAddr(
    val key: PathKey,
    val recvInfo: QuicheRecvInfo,
    val made: Made,
    val prior: RttPrior,
    val socket: SocketTicket,
    target: SendTarget,
) {
    val info = RecvFrom.Info(recvInfo)
    val out = Outgoing.To(target, socket)

    /** How the connection's datagrams are sent while this is its one endpoint. */
    val fixed = Sorting.Fixed(out)
}

/** Who made a [LocalAddr]'s sockaddr and receive info. */
internal sealed interface Made {
    /** The connect's own, freed with the path the connection was opened on. */
    data object ByEdge : Made

    class Here(
        val sockAddr: EncodedSockAddr,
    ) : Made
}

/** A local endpoint and the destination connection id quiche linked to its path: the two never separate. */
internal class Linked(
    val local: LocalAddr,
    val dcidSeq: Long,
)

/** Whether a stranded endpoint's path holds a destination id. */
internal sealed interface LinkedId {
    data object None : LinkedId

    class Seq(
        val seq: Long,
    ) : LinkedId
}

/** Why a moved socket's endpoint has no path being validated. */
internal enum class Unprobed { NoSpareId, ValidationFailed, MigrationRefused }

/**
 * Where a client's path stands. Every destination id it holds is in exactly one [Linked]; the step that drops one
 * retires it. Each endpoint is one of the loop's sockets: [Settled] and [Asked] have one, and a move owns the second,
 * opened beside the carrying one, until it is promoted or closed.
 */
internal sealed interface Move {
    /** The connection's one path leaves from [on]. */
    class Settled(
        val on: Linked,
    ) : Move

    /**
     * A second socket was asked of the loop as [ticket], and the loop has not answered: the one path still leaves from
     * [from], and only an answer naming [ticket] moves on from here.
     */
    class Asked(
        val from: Linked,
        val ticket: SocketTicket,
    ) : Move

    /**
     * A second socket leaves from [to], whose path is being validated; quiche's active path, and the carrying socket,
     * are still [from]'s.
     */
    class Probing(
        val from: Linked,
        val to: Linked,
        /** The probes of this move that found no path. */
        val failed: Int,
        val cadence: Cadence,
    ) : Move

    /**
     * A second socket leaves from [at], whose path is not being validated; quiche's active path, and the carrying
     * socket, are still [from]'s.
     */
    class Stranded(
        val from: Linked,
        val at: LocalAddr,
        val why: Unprobed,
        val retry: Retry,
        /** The id quiche linked to [at]'s path, kept so the path can be probed again. */
        val id: LinkedId,
    ) : Move
}

/**
 * This move on another usable network: what failed on the network before says nothing of this one, so a stranded
 * endpoint is probed again at [now] and the move backs off afresh.
 */
internal fun Move.onNewNetwork(now: Nanos): Move =
    when (this) {
        is Move.Stranded -> Move.Stranded(from, at, why, Retry(now, 0), id)
        is Move.Probing -> Move.Probing(from, to, 0, cadence)
        is Move.Settled, is Move.Asked -> this
    }

/** Hands [each] every endpoint this move holds. */
internal fun Move.locals(each: (LocalAddr) -> Unit) {
    when (this) {
        is Move.Settled -> each(on.local)
        is Move.Asked -> each(from.local)
        is Move.Probing -> {
            each(from.local)
            each(to.local)
        }
        is Move.Stranded -> {
            each(from.local)
            each(at)
        }
    }
}

/** The endpoint of the socket carrying the connection while this move stands. */
internal fun Move.carrying(): LocalAddr =
    when (this) {
        is Move.Settled -> on.local
        is Move.Asked -> from.local
        is Move.Probing -> from.local
        is Move.Stranded -> from.local
    }

/** The endpoint of the second socket this move owns, or the carrying one when it owns none. */
internal fun Move.second(): LocalAddr =
    when (this) {
        is Move.Settled -> on.local
        is Move.Asked -> from.local
        is Move.Probing -> to.local
        is Move.Stranded -> at
    }

/** Which endpoint of a move a datagram is read on. */
internal enum class ReadOn { Carrying, Second }

/** Whether the second socket a loop lost or refused was the one a move held or asked for, which then ended. */
internal enum class SecondHeld { Lost, Stale }

/** Whether an answer of the loop names the socket a move asked for and still awaits. */
internal sealed interface Awaited {
    class Ask(
        val asked: Move.Asked,
    ) : Awaited

    /** No move awaits the socket the answer names: it was given up or replaced, and the answer changes nothing. */
    data object Stale : Awaited
}

/** What the loop losing socket [ticket] does to this move: ends it if [ticket] is the second socket it holds open. */
internal fun Move.onLost(ticket: SocketTicket): SecondHeld =
    when (this) {
        is Move.Settled, is Move.Asked -> SecondHeld.Stale
        is Move.Probing -> if (to.local.socket == ticket) SecondHeld.Lost else SecondHeld.Stale
        is Move.Stranded -> if (at.socket == ticket) SecondHeld.Lost else SecondHeld.Stale
    }

/**
 * Whether the path quiche carries on leaves from a validated endpoint: not while a second socket's path is probed or
 * stranded, nor while a move [held] waits for a spare id. A socket asked of the loop adds no path to quiche.
 */
internal fun Move.address(held: HeldMove): PeerAddress =
    when (this) {
        is Move.Probing, is Move.Stranded -> PeerAddress.Unvalidated
        is Move.Settled, is Move.Asked ->
            when (held) {
                is HeldMove.None -> PeerAddress.Validated
                is HeldMove.Waiting -> PeerAddress.Unvalidated
            }
    }

/** Whether a move is open for the rebind ladder: from its ask on, or while one [held] waits for a spare id. */
internal fun Move.moving(held: HeldMove): Moving =
    when (this) {
        is Move.Asked, is Move.Probing, is Move.Stranded -> Moving.Open
        is Move.Settled ->
            when (held) {
                is HeldMove.None -> Moving.Settled
                is HeldMove.Waiting -> Moving.Open
            }
    }

/** Whether this move awaits the loop's answer for socket [ticket]. */
internal fun Move.awaiting(ticket: SocketTicket): Awaited =
    when (this) {
        is Move.Asked -> if (this.ticket == ticket) Awaited.Ask(this) else Awaited.Stale
        is Move.Settled, is Move.Probing, is Move.Stranded -> Awaited.Stale
    }

/**
 * Which endpoint a datagram that arrived on socket [on] is read on. While a move waits for a spare id, quiche has no
 * path for the second endpoint, so what arrives there is read on the carrying path, which can carry the id the peer
 * sends.
 */
internal fun Move.readOn(on: SocketTicket): ReadOn =
    when (this) {
        is Move.Settled, is Move.Asked -> ReadOn.Carrying
        is Move.Probing -> if (to.local.socket == on) ReadOn.Second else ReadOn.Carrying
        is Move.Stranded -> if (why != Unprobed.NoSpareId && at.socket == on) ReadOn.Second else ReadOn.Carrying
    }

/** Whether a path answered within a span. */
internal enum class Answered { Not, Yes }

/** The connection's one path, from the endpoint its connect opened on, the loop's first socket. */
internal fun QuicheConnRef.firstMove(first: SinglePath): Move =
    Move.Settled(
        Linked(
            LocalAddr(
                api.decodePathKey(first.local.address),
                first.recvInfo,
                Made.ByEdge,
                RttPrior.CONFIGURED,
                SocketTicket.FIRST,
                first.target,
            ),
            0L,
        ),
    )

/** How many bytes quiche may write into one datagram while this move stands. */
internal fun Move.window(full: Window): Window =
    when (this) {
        is Move.Settled, is Move.Asked -> full
        is Move.Probing, is Move.Stranded -> full.whileValidating()
    }

internal fun Move.sorting(): Sorting =
    when (this) {
        is Move.Settled -> on.local.fixed
        is Move.Asked -> from.local.fixed
        is Move.Probing, is Move.Stranded -> Sorting.ByAddress
    }

/** Where a datagram quiche wrote from the local address at [from] goes: out of the socket that holds that address. */
internal fun Move.outgoing(
    q: QuicheConnRef,
    from: Long,
): Outgoing {
    val carrying = carrying()
    if (q.addressIs(from, carrying.key)) return carrying.out
    val second = second()
    return if (second !== carrying && q.addressIs(from, second.key)) second.out else Outgoing.Dropped
}

/** Offers the time the next challenge, abandonment or probe of this move falls due; nothing when settled. */
internal fun Move.offerWake(sink: WakeSink) =
    when (this) {
        is Move.Stranded -> sink.offer(retry.due)
        is Move.Probing -> {
            sink.offer(cadence.challengeDue)
            sink.offer(cadence.abandonAt)
        }
        is Move.Settled, is Move.Asked -> Unit
    }

/** Hands [each] the endpoint kept, if one is. */
internal fun Previous.held(each: (LocalAddr) -> Unit) =
    when (this) {
        is Previous.Held -> each(local)
        is Previous.None -> Unit
    }

/** Retires the destination id [id] holds, if it holds one. */
internal fun QuicheConnRef.retire(id: LinkedId) =
    when (id) {
        is LinkedId.Seq -> retire(id.seq)
        is LinkedId.None -> Unit
    }

internal fun QuicheConnRef.retire(seq: Long) {
    // A refusal leaves the id with quiche until the connection ends; no path uses it.
    api.connRetireDcid(handle, seq)
}
