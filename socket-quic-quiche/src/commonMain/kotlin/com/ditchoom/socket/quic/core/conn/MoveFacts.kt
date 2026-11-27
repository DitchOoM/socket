package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.path.MoveAnswer
import com.ditchoom.socket.quic.core.path.Promoted
import com.ditchoom.socket.quic.core.path.SecondLoss
import com.ditchoom.socket.quic.core.path.Want
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.SocketTicket

// What the loop and the edge tell a client's paths about its sockets: each answers inside, one fact at a time.

/**
 * Inside: a client's second socket leaves from [to]'s address. The connection probes the path from it and migrates once
 * it is validated, the carrying socket carrying until then; before its handshake is confirmed (RFC 9000 §9), or when
 * the peer forbade migration, it cannot follow. A socket of an ask the connection no longer awaits changes nothing.
 */
internal fun ConnParts.follow(
    now: Nanos,
    to: MoveTo,
): MoveAnswer {
    val client =
        when (val paths = slots.paths) {
            is Paths.Client -> paths.paths
            is Paths.Server -> return MoveAnswer.Following
        }
    val answer = client.move(now, to.local, to.prior, to.ticket, gate())
    val mark =
        when (answer) {
            MoveAnswer.Following -> PathMark.Moved
            MoveAnswer.Refused, MoveAnswer.NoUsablePath -> PathMark.Refused
            MoveAnswer.Stale -> return answer
        }
    network.moved()
    slots.ack.mustWrite()
    flight.path(now, mark)
    return answer
}

/**
 * Inside: the second socket [ticket] names is gone. The move it served ends and the connection stays where it is; a
 * landing on it migrates back, and what the lost path had in flight is resent at once.
 */
internal fun ConnParts.secondLost(
    now: Nanos,
    ticket: SocketTicket,
): SecondLoss =
    when (val paths = slots.paths) {
        is Paths.Client ->
            paths.paths.lost(now, ticket, life.view()).also {
                when (it) {
                    SecondLoss.MoveEnded, SecondLoss.NoPathBack -> flight.path(now, PathMark.SecondLost)
                    SecondLoss.MovedBack -> flight.path(now, PathMark.SecondLost).also { resendNow() }
                    SecondLoss.Stale -> Unit
                }
            }
        is Paths.Server -> SecondLoss.Stale
    }

/** Inside: the loop promoted the socket [ticket] names; a landing on it settles, and the old path's flight is resent at once. */
internal fun ConnParts.promoted(ticket: SocketTicket) =
    when (val paths = slots.paths) {
        is Paths.Client ->
            when (paths.paths.promoted(ticket)) {
                Promoted.Settled -> resendNow()
                Promoted.Stale -> Unit
            }
        is Paths.Server -> Unit
    }

/** quiche writes now what it requeued: the probes it owes leave in this turn, not at its next timer. */
private fun ConnParts.resendNow() {
    slots.ack.mustWrite()
    timer.nudge()
}

/**
 * Inside: a client's socket is wanted on [wanted]'s address. Before its handshake is confirmed it moves at once, and
 * the connection, which cannot follow it (RFC 9000 §9), refuses the move and stays; after, it moves once the path from
 * there can be probed.
 */
internal fun ConnParts.want(
    now: Nanos,
    wanted: MoveWanted,
): Want {
    val client =
        when (val paths = slots.paths) {
            is Paths.Client -> paths.paths
            is Paths.Server -> return Want.Moving
        }
    val want =
        when (handshake.confirmation()) {
            Confirmation.Pending -> client.hold.moveNow(wanted.bind, wanted.prior)
            Confirmation.Confirmed -> client.hold.want(now, wanted.bind, wanted.prior)
        }
    when (want) {
        Want.Moving -> Unit
        Want.Waiting -> flight.path(now, PathMark.Held)
        Want.NoUsablePath -> flight.path(now, PathMark.Refused)
    }
    return want
}
