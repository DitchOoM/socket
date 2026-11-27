package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.SingleOwner
import com.ditchoom.socket.quic.core.time.RttPrior
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
 * opened beside the carrying one, until the loop promoted it ([Landing]) or it is closed.
 */
@SingleOwner(owner = "path/PathsValidation.kt")
internal sealed interface Move {
    /** The connection's one path leaves from [on]. */
    class Settled(
        val on: Linked,
    ) : Move

    /**
     * A move whose second socket is asked of the loop, or whose path is not yet migrated to: it can be given up. Each
     * open move keeps the want it was asked for, its `toward`.
     */
    sealed interface Unfinished : Move

    /**
     * A second socket was asked of the loop as [ticket], and the loop has not answered: the one path still leaves from
     * [from], and only an answer naming [ticket] moves on from here.
     */
    class Asked(
        val from: Linked,
        val ticket: SocketTicket,
        val toward: MoveWanted,
    ) : Unfinished

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
        val toward: MoveWanted,
    ) : Unfinished

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
        val toward: MoveWanted,
    ) : Unfinished

    /**
     * quiche migrated to [to]'s validated path and the loop is asked to promote its socket. [from]'s socket, id and
     * flight are kept until the loop says it promoted [to]'s socket, or lost it, and the connection migrates back. The
     * move was asked [toward] its want.
     */
    class Landing(
        val from: Linked,
        val to: Linked,
        val toward: MoveWanted,
    ) : Move
}

/** Whether the second socket a loop refused was the one a move asked for, which then ended. */
internal enum class SecondHeld { Lost, Stale }

/** Whether an answer of the loop names the socket a move asked for and still awaits. */
internal sealed interface Awaited {
    class Ask(
        val asked: Move.Asked,
    ) : Awaited

    /** No move awaits the socket the answer names: it was given up or replaced, and the answer changes nothing. */
    data object Stale : Awaited
}

/** Whether this move awaits the loop's answer for socket [ticket]. */
internal fun Move.awaiting(ticket: SocketTicket): Awaited =
    when (this) {
        is Move.Asked -> if (this.ticket == ticket) Awaited.Ask(this) else Awaited.Stale
        is Move.Settled, is Move.Probing, is Move.Stranded, is Move.Landing -> Awaited.Stale
    }

/** What the loop losing or promoting a second socket meets: the move that holds it, or none. */
internal sealed interface Holding {
    /** The second socket of a move not yet migrated to: the move ends. */
    class Unfinished(
        val move: Move.Unfinished,
    ) : Holding

    /** The socket quiche migrated to, not yet promoted. */
    class Landing(
        val landing: Move.Landing,
    ) : Holding

    /** No move holds the socket the loop named: it was given up, replaced or promoted already, and nothing changes. */
    data object Stale : Holding
}

/** Which move holds socket [ticket] open as its second one. */
internal fun Move.holding(ticket: SocketTicket): Holding =
    when (this) {
        is Move.Settled, is Move.Asked -> Holding.Stale
        is Move.Probing -> if (to.local.socket == ticket) Holding.Unfinished(this) else Holding.Stale
        is Move.Stranded -> if (at.socket == ticket) Holding.Unfinished(this) else Holding.Stale
        is Move.Landing -> if (to.local.socket == ticket) Holding.Landing(this) else Holding.Stale
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
