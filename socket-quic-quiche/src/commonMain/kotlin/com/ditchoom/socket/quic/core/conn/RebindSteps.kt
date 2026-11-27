package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.path.ClientPaths
import com.ditchoom.socket.quic.core.path.MoveGate
import com.ditchoom.socket.quic.core.path.Rebind
import com.ditchoom.socket.quic.core.path.Rebound
import com.ditchoom.socket.quic.core.path.SecondHeld
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.SocketTicket

// A client's rebinds of a silent path ([com.ditchoom.socket.quic.core.path.Rebinds]), served from its work and its turn.
// A connection past its close point asks for no socket: quiche holds the close on the one it has.

/** Inside: the edge read the client's path silent and wants its socket on a fresh port of [wanted]'s address. */
internal fun ConnParts.rebind(
    now: Nanos,
    wanted: MoveWanted,
) = when (val paths = slots.paths) {
    is Paths.Client -> if (running()) record(now, paths.paths.rebinds.asked(now, wanted.bind, wanted.prior, gate())) else Unit
    is Paths.Server -> Unit
}

/**
 * Inside, each serve of a client: a rung of the rebind ladder that fell due is asked; past the close point the ladder
 * stops, so it neither asks nor wakes the loop for a rung it will never ask.
 */
internal fun ConnParts.stepRebinds(
    now: Nanos,
    client: ClientPaths,
) = if (running()) record(now, client.rebinds.step(now, gate())) else client.rebinds.stop()

/** Inside: news at [now] restarts a client's rebind ladder at its first wait, toward [bind]. */
internal fun ConnParts.renewRebinds(
    now: Nanos,
    bind: Rebind,
) = when (val paths = slots.paths) {
    is Paths.Client -> paths.paths.rebinds.renewed(now, bind)
    is Paths.Server -> Unit
}

/**
 * Inside: the loop refused the socket [ticket] names. The move that asked for it ends, which the edge is told, and a
 * rebind may ask again on its ladder; a refusal of an ask the connection no longer awaits is [SecondHeld.Stale].
 */
internal fun ConnParts.openRefused(ticket: SocketTicket): SecondHeld =
    when (val paths = slots.paths) {
        is Paths.Client -> paths.paths.refused(ticket).also { if (it == SecondHeld.Lost) paths.paths.rebinds.answered() }
        is Paths.Server -> SecondHeld.Stale
    }

/** Inside: the loop's second socket was followed or declined; one declined ended its move, which the edge is told. */
internal fun ConnParts.openAnswered() =
    when (val paths = slots.paths) {
        is Paths.Client -> paths.paths.rebinds.answered()
        is Paths.Server -> Unit
    }

private fun ConnParts.running(): Boolean = life.view() is Life.Running

/** Whether the handshake lets a client move yet (RFC 9000 §9). */
internal fun ConnParts.gate(): MoveGate =
    when (handshake.confirmation()) {
        Confirmation.Pending -> MoveGate.Shut
        Confirmation.Confirmed -> MoveGate.Open
    }

private fun ConnParts.record(
    now: Nanos,
    rebound: Rebound,
) = when (rebound) {
    Rebound.Asked -> flight.path(now, PathMark.Rebind)
    Rebound.Stale -> flight.path(now, PathMark.RebindStale)
    Rebound.Served -> flight.path(now, PathMark.RebindServed)
    Rebound.Held -> flight.path(now, PathMark.RebindHeld)
    Rebound.Forbidden -> flight.path(now, PathMark.RebindForbidden)
    Rebound.GaveUp -> flight.path(now, PathMark.RebindGaveUp)
    Rebound.Deferred, Rebound.Rested -> Unit
}
