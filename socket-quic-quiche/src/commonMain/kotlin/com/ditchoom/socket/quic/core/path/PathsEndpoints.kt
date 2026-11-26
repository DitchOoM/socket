package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.SendTarget
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
 * retires it. Each endpoint is one of the loop's sockets: [Settled] has one, and a move owns the second, opened beside
 * the carrying one, until it is promoted or closed.
 */
internal sealed interface Move {
    /** The connection's one path leaves from [on]. */
    class Settled(
        val on: Linked,
    ) : Move

    /** A second socket leaves from [to], whose path is being validated; quiche's active path, and the carrying socket, are still [from]'s. */
    class Probing(
        val from: Linked,
        val to: Linked,
        /** The probes of this move that found no path. */
        val failed: Int,
        val cadence: Cadence,
    ) : Move

    /** A second socket leaves from [at], whose path is not being validated; quiche's active path, and the carrying socket, are still [from]'s. */
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
        is Move.Settled -> this
    }

/** Hands [each] every endpoint this move holds. */
internal fun Move.locals(each: (LocalAddr) -> Unit) {
    when (this) {
        is Move.Settled -> each(on.local)
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
        is Move.Probing -> from.local
        is Move.Stranded -> from.local
    }

/** The endpoint of the second socket this move owns, or the carrying one when it owns none. */
internal fun Move.second(): LocalAddr =
    when (this) {
        is Move.Settled -> on.local
        is Move.Probing -> to.local
        is Move.Stranded -> at
    }

/**
 * Whether a datagram that arrived on socket [on] was read on this move's second endpoint. While a move waits for a
 * spare id, quiche has no path for the second endpoint, so what arrives there is read on the carrying path, which can
 * carry the id the peer sends.
 */
internal fun Move.readsOnSecond(on: SocketTicket): Boolean =
    when (this) {
        is Move.Settled -> false
        is Move.Probing -> to.local.socket == on
        is Move.Stranded -> why != Unprobed.NoSpareId && at.socket == on
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
        is Move.Settled -> full
        is Move.Probing, is Move.Stranded -> full.whileValidating()
    }

internal fun Move.sorting(): Sorting =
    when (this) {
        is Move.Settled -> on.local.fixed
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
        is Move.Settled -> Unit
    }

/** Hands [each] the endpoint kept, if one is. */
internal fun Previous.held(each: (LocalAddr) -> Unit) =
    when (this) {
        is Previous.Held -> each(local)
        is Previous.None -> Unit
    }
