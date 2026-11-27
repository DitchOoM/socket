package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.PeerAddress
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.SocketTicket

// How each arm of a client's [Move] reads: one `when` per question the connection asks of its paths.

/**
 * This move on another usable network: what failed on the network before says nothing of this one, so a stranded
 * endpoint is probed again at [now] and the move backs off afresh.
 */
internal fun Move.onNewNetwork(now: Nanos): Move =
    when (this) {
        is Move.Stranded -> Move.Stranded(from, at, why, Retry(now, 0), id, toward)
        is Move.Probing -> Move.Probing(from, to, 0, cadence, toward)
        is Move.Settled, is Move.Asked, is Move.Landing -> this
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
        is Move.Landing -> {
            each(from.local)
            each(to.local)
        }
    }
}

/** The endpoint quiche's active path leaves from while this move stands. */
internal fun Move.carrying(): LocalAddr =
    when (this) {
        is Move.Settled -> on.local
        is Move.Asked -> from.local
        is Move.Probing -> from.local
        is Move.Stranded -> from.local
        is Move.Landing -> to.local
    }

/** The endpoint of the other socket this move holds open, or the carrying one when it holds none. */
internal fun Move.second(): LocalAddr =
    when (this) {
        is Move.Settled -> on.local
        is Move.Asked -> from.local
        is Move.Probing -> to.local
        is Move.Stranded -> at
        is Move.Landing -> from.local
    }

/** Which endpoint of a move a datagram is read on. */
internal enum class ReadOn { Carrying, Second }

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
        is Move.Landing -> if (from.local.socket == on) ReadOn.Second else ReadOn.Carrying
    }

/**
 * How a close and the ACK hold read the path: unvalidated while a second socket's path is probed or stranded. A
 * socket asked of the loop, a move waiting for a spare id and a landing add no unvalidated path to quiche.
 */
internal fun Move.address(): PeerAddress =
    when (this) {
        is Move.Probing, is Move.Stranded -> PeerAddress.Unvalidated
        is Move.Settled, is Move.Asked, is Move.Landing -> PeerAddress.Validated
    }

/**
 * How an idle timeout reads: with no usable path while a second socket's path is probed or stranded, or while a move
 * [held] waits for a spare id that never came; else as the idle timeout it is.
 */
internal fun Move.idleEnd(held: HeldMove): ConnEnd =
    when (this) {
        is Move.Probing, is Move.Stranded -> ConnEnd.NoUsablePath
        is Move.Settled, is Move.Asked, is Move.Landing ->
            when (held) {
                is HeldMove.None -> ConnEnd.IdleTimeout
                is HeldMove.Waiting -> ConnEnd.NoUsablePath
            }
    }

/** Whether a move is open for the rebind ladder: from its ask until quiche migrated, while one [held] waits, or landing. */
internal fun Move.moving(held: HeldMove): Moving =
    when (this) {
        is Move.Asked, is Move.Probing, is Move.Stranded -> Moving.Open
        is Move.Landing -> Moving.Landing
        is Move.Settled ->
            when (held) {
                is HeldMove.None -> Moving.Settled
                is HeldMove.Waiting -> Moving.Open
            }
    }

/** How many bytes quiche may write into one datagram while this move stands. */
internal fun Move.window(full: Window): Window =
    when (this) {
        is Move.Settled, is Move.Asked, is Move.Landing -> full
        is Move.Probing, is Move.Stranded -> full.whileValidating()
    }

internal fun Move.sorting(): Sorting =
    when (this) {
        is Move.Settled -> on.local.fixed
        is Move.Asked -> from.local.fixed
        is Move.Probing, is Move.Stranded, is Move.Landing -> Sorting.ByAddress
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

/** Offers the time the next challenge, abandonment or probe of this move falls due; nothing when settled or landing. */
internal fun Move.offerWake(sink: WakeSink) =
    when (this) {
        is Move.Stranded -> sink.offer(retry.due)
        is Move.Probing -> {
            sink.offer(cadence.challengeDue)
            sink.offer(cadence.abandonAt)
        }
        is Move.Settled, is Move.Asked, is Move.Landing -> Unit
    }
