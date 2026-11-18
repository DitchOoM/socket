package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.RetiredId
import com.ditchoom.socket.quic.core.life.SpareId
import com.ditchoom.socket.quic.core.path.MoveAnswer
import com.ditchoom.socket.quic.core.path.Outgoing
import com.ditchoom.socket.quic.core.path.PathRead
import com.ditchoom.socket.quic.core.path.PathStep
import com.ditchoom.socket.quic.core.path.RecvFrom
import com.ditchoom.socket.quic.core.path.Sorting
import com.ditchoom.socket.quic.core.path.Window
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.NativeBytes

// What every role's paths answer, asked the same way: each is one `when` over the slot.

/** What quiche is told about a datagram that came from [from]. */
internal fun Paths.recvFrom(from: PathKey): RecvFrom =
    when (this) {
        is Paths.Client -> paths.recvFrom()
        is Paths.Server -> paths.recvInfo(from)
    }

/** Hands quiche the datagram of [length] bytes at the start of [bytes] that came [from], unless no path takes it. */
internal fun ConnParts.feed(
    bytes: NativeBytes,
    length: Int,
    from: PathKey,
) = when (val info = slots.paths.recvFrom(from)) {
    is RecvFrom.Info -> {
        slots.ack.received()
        ingress.direct(bytes, length, info.recvInfo, flight)
    }
    is RecvFrom.TooManyAddresses -> ingress.refused(length, from, flight)
}

/**
 * Where a CONNECTION_CLOSE quiche wrote for a path no socket of ours holds goes instead: a client's socket reaches the
 * same peer from wherever it is now; a server has no socket for an address it does not know.
 */
internal fun Paths.closeRedirect(): Outgoing =
    when (this) {
        is Paths.Client -> paths.fromSocket()
        is Paths.Server -> Outgoing.Dropped
    }

/** Where a datagram quiche wrote for the path [address] names goes: a client's own address, a server's peer's. */
internal fun Paths.outgoing(address: Long): Outgoing =
    when (this) {
        is Paths.Client -> paths.outgoing(address)
        is Paths.Server -> paths.outgoing(address)
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

/** The connection's statistics, read now. */
internal fun Paths.stats(): QuicStatsRead =
    when (this) {
        is Paths.Client -> paths.stats()
        is Paths.Server -> paths.stats()
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
internal fun ConnParts.stepPaths(now: Nanos): PathStep =
    when (val paths = slots.paths) {
        is Paths.Client -> {
            val step = paths.paths.step(now)
            if (step != PathStep.Steady) slots.ack.mustWrite()
            when (step) {
                PathStep.Steady, PathStep.NoUsablePath -> Unit
                PathStep.ProbeFailed -> flight.path(now, PathMark.ProbeFailed)
                PathStep.Challenged -> flight.path(now, PathMark.Challenged)
                PathStep.Reprobed -> {
                    flight.path(now, PathMark.Reprobed)
                    timer.nudge()
                }
                PathStep.Migrated -> {
                    flight.path(now, PathMark.Migrated)
                    timer.nudge()
                }
            }
            step
        }
        is Paths.Server -> {
            paths.paths.step()
            PathStep.Steady
        }
    }

/**
 * Inside: the OS reports a usable network, another one or the same one back. Quiche sends its probes at once, and a
 * client's unfinished move is followed afresh on it: the probes that failed before say nothing of this network.
 */
internal fun ConnParts.networkUsable(now: Nanos) {
    slots.ack.mustWrite()
    timer.nudge()
    when (val paths = slots.paths) {
        is Paths.Client -> paths.paths.networkChanged(now)
        is Paths.Server -> Unit
    }
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
    to: MoveTo,
): MoveAnswer {
    val client =
        when (val paths = slots.paths) {
            is Paths.Client -> paths.paths
            is Paths.Server -> return MoveAnswer.Following
        }
    network.moved()
    slots.ack.mustWrite()
    val answer =
        when (handshake.confirmation()) {
            Confirmation.Pending -> MoveAnswer.Refused
            Confirmation.Confirmed -> client.move(now, to.local, to.prior)
        }
    flight.path(
        now,
        when (answer) {
            MoveAnswer.Following -> PathMark.Moved
            MoveAnswer.Refused, MoveAnswer.NoUsablePath -> PathMark.Refused
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

/** Whether a client's socket is moving, or a server's peer is on more than one address. */
internal enum class PathCalm { Settled, Moving }

internal fun Paths.calm(): PathCalm =
    when (this) {
        is Paths.Client -> if (paths.unsettled()) PathCalm.Moving else PathCalm.Settled
        is Paths.Server ->
            when (paths.sorting()) {
                is Sorting.Fixed -> PathCalm.Settled
                is Sorting.ByAddress -> PathCalm.Moving
            }
    }
