package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.RetiredId
import com.ditchoom.socket.quic.core.life.SpareId
import com.ditchoom.socket.quic.core.path.MoveAnswer
import com.ditchoom.socket.quic.core.path.PathRead
import com.ditchoom.socket.quic.core.path.PathStep
import com.ditchoom.socket.quic.core.path.RecvFrom
import com.ditchoom.socket.quic.core.path.Sorting
import com.ditchoom.socket.quic.core.path.Window
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink

// What every role's paths answer, asked the same way: each is one `when` over the slot.

/** What quiche is told about a datagram that came from [from]. */
internal fun Paths.recvFrom(from: PathKey): RecvFrom =
    when (this) {
        is Paths.Client -> paths.recvFrom()
        is Paths.Server -> paths.recvInfo(from)
    }

internal fun Paths.sendInfo(): QuicheSendInfo =
    when (this) {
        is Paths.Client -> paths.sendInfo
        is Paths.Server -> paths.sendInfo
    }

internal fun Paths.window(full: Window): Window =
    when (this) {
        is Paths.Client -> paths.window(full)
        is Paths.Server -> paths.window(full)
    }

internal fun Paths.sorting(): Sorting =
    when (this) {
        is Paths.Client -> paths.sorting()
        is Paths.Server -> paths.sorting()
    }

/** The statistics of the path the connection was opened on. */
internal fun Paths.read(): PathRead =
    when (this) {
        is Paths.Client -> paths.read()
        is Paths.Server -> paths.read()
    }

/** The connection is established: each role hands out, or asks the loop for, the ids a move needs. */
internal fun Paths.established() =
    when (this) {
        is Paths.Client -> {
            paths.established()
            Unit
        }
        is Paths.Server -> paths.established()
    }

/**
 * Inside, each serve: reads the paths' news. A client that just migrated has quiche send its probes on the new path
 * at once, so what the old path lost is resent now rather than at its next timer.
 */
internal fun ConnParts.stepPaths(now: Nanos) =
    when (val paths = slots.paths) {
        is Paths.Client ->
            when (paths.paths.step(now)) {
                PathStep.Steady -> Unit
                PathStep.Migrated -> {
                    flight.path(now, PathMark.Migrated)
                    timer.nudge()
                }
            }
        is Paths.Server -> paths.paths.step()
    }

/** How an end quiche reported reads: a client whose socket moved and found no validated path did not merely go idle. */
internal fun Paths.endOf(end: ConnEnd): ConnEnd =
    when (this) {
        is Paths.Client -> if (end is ConnEnd.IdleTimeout && paths.unsettled()) ConnEnd.NoUsablePath else end
        is Paths.Server -> end
    }

/**
 * Inside: a client's socket now leaves from [local]. The connection probes the path from it and migrates once it is
 * validated; before its handshake is confirmed (RFC 9000 §9), or when the peer forbade migration, it cannot follow.
 */
internal fun ConnParts.follow(
    now: Nanos,
    local: PathKey,
): MoveAnswer {
    val client =
        when (val paths = slots.paths) {
            is Paths.Client -> paths.paths
            is Paths.Server -> return MoveAnswer.Following
        }
    network.moved()
    val answer =
        when (handshake.confirmation()) {
            Confirmation.Pending -> MoveAnswer.Refused
            Confirmation.Confirmed -> client.move(now, local)
        }
    flight.path(
        now,
        when (answer) {
            MoveAnswer.Following -> PathMark.Moved
            MoveAnswer.Refused -> PathMark.Refused
        },
    )
    return answer
}

/** Any thread: how many new ids the loop should route to a server connection; taken once. */
internal fun Paths.idsWanted(): Int =
    when (this) {
        is Paths.Client -> 0
        is Paths.Server -> paths.wanted.take()
    }

/** Any thread: the ids a server connection's peer retired, taken once; a client's peer retires none that are routed. */
internal fun Paths.idsRetired(): List<RetiredId> =
    when (this) {
        is Paths.Client -> emptyList()
        is Paths.Server -> paths.retired.take()
    }

/** Inside: the ids the loop routed to a server connection go to quiche. */
internal fun Paths.issue(spare: List<SpareId>) =
    when (this) {
        is Paths.Client -> Unit
        is Paths.Server -> paths.issue(spare)
    }

/** Offers the wake times of the paths: a client whose move found no path is probed again when its retry falls due. */
internal fun Paths.offerWake(sink: WakeSink) =
    when (this) {
        is Paths.Client -> paths.offerWake(sink)
        is Paths.Server -> Unit
    }
