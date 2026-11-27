@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: every Paths variant lives in one.

package com.ditchoom.socket.quic.core.path

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.PeerMigrationPermission
import com.ditchoom.socket.quic.ProbeOutcome
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.connPeerMigrationPermission
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.life.PeerAddress
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.SocketTicket
import kotlin.random.Random

/**
 * A client's paths. A move is made before it breaks: the new endpoint's socket opens beside the carrying one and its path
 * is probed (RFC 9000 §8.2) with datagrams of at most 1200 bytes; once it validates, quiche migrates, and the connection
 * settles there when the loop says it promoted the socket. A move refused, lost or failing while the old path answers
 * ends with the connection where it was; a socket lost before its promotion takes quiche back to the old path.
 */
@Confined
internal class ClientPaths(
    private val q: QuicheConnRef,
    /** The path the connection was opened on: its send info and statistics. */
    val opened: SinglePath,
    private val factory: BufferFactory,
    ids: IdIssuer,
    random: Random,
    free: (QuicheRecvInfo, Made) -> Unit,
    ask: (SocketAsk) -> Unit,
    val silence: PathSilence,
) {
    private val state = MoveState(q.firstMove(opened))
    private var move: Move
        get() = state.move
        set(value) = state.stands(value)
    private val events = PathEvents(q, factory)
    private val peer = TowardPeer(q, opened)
    private val ears = PeerHearing(q)
    private val costs = MoveCosts(q, ids, random, factory, free, ask)
    private val landings = MoveLandings(q, peer, costs, silence)
    private val validation = MoveValidation(q, state, events, peer, ears, costs, silence)

    /**
     * Where the socket is wanted, and whether it waits to move there; a new move gives up an unfinished one first, and
     * names its socket with the next ticket. From the ask on the move is open ([Move.Asked]). A landing is never given
     * up: the move waits until the loop promoted or lost its socket.
     */
    val hold =
        MoveHold(q, ears) { wanted ->
            when (val now = move) {
                is Move.Landing -> Opening.AfterLanding
                is Move.Settled -> ask(now.on, wanted)
                // Before the new socket's open is asked, so no promotion of the old probe can follow it to the loop.
                is Move.Unfinished -> ask(costs.abandon(now), wanted)
            }
        }

    /** Where the move last asked of the loop goes. */
    private var toward: Toward = Toward.Nowhere

    /** The rebinds the active path's silence asks for, each through [hold]; one it gives up drops an unfinished move. */
    val rebinds: Rebinds =
        Rebinds(q, silence, hold, { move.moving(hold.held) }) {
            when (val now = move) {
                is Move.Unfinished -> move = Move.Settled(costs.abandon(now))
                is Move.Settled, is Move.Landing -> Unit
            }
        }

    /** What quiche is told of a datagram on socket [on] at [now] ([Move.readOn]); on the carrying socket the path answered. */
    fun recvFrom(
        on: SocketTicket,
        now: Nanos,
    ): RecvFrom {
        ears.arrived()
        if (move.readOn(on) == ReadOn.Second) return move.second().info
        if (move.carrying().socket == on) {
            state.heard()
            silence.answered(now)
        }
        return move.carrying().info
    }

    fun window(full: Window): Window = move.window(full)

    fun sorting(): Sorting = move.sorting()

    /** Where a datagram quiche wrote from the local address at [from] goes: out of the socket that holds that address. */
    fun outgoing(from: Long): Outgoing = move.outgoing(q, from)

    /** Where the carrying socket sends, whichever address quiche wrote a datagram for. */
    fun fromSocket(): Outgoing.To = move.carrying().out

    /** The connection is established: the peer is given the spare ids a move needs. */
    fun established(): Issued = costs.established()

    /**
     * The loop's second socket [ticket] leaves from [local], assumed [prior]: its path is probed, or, with [gate] shut or
     * the peer forbidding migration, the socket is declined. A socket the connection no longer awaits changes nothing.
     */
    fun move(
        now: Nanos,
        local: PathKey,
        prior: RttPrior,
        ticket: SocketTicket,
        gate: MoveGate,
    ): MoveAnswer {
        val asked =
            when (val awaited = move.awaiting(ticket)) {
                is Awaited.Ask -> awaited.asked
                is Awaited.Stale -> return MoveAnswer.Stale
            }
        when (q.api.connPeerMigrationPermission(q.handle)) {
            is PeerMigrationPermission.Permitted -> if (gate == MoveGate.Shut) return decline(asked)
            is PeerMigrationPermission.Forbidden, is PeerMigrationPermission.NotYetNegotiated -> return decline(asked)
        }
        costs.topUp()
        val hearing = ears.judge(now)
        val at = peer.endpointAt(local, prior, ticket, factory)
        val from = asked.from
        state.validating()
        return when (val probed = peer.probe(at)) {
            is ProbeOutcome.Probed -> {
                move = Move.Probing(from, Linked(at, probed.dcidSeq), 0, q.cadenceFrom(now))
                MoveAnswer.Following
            }
            is ProbeOutcome.Rejected -> {
                move = Move.Stranded(from, at, Unprobed.NoSpareId, q.retryAt(now, 0), LinkedId.None)
                if (probed.endsMove(hearing)) MoveAnswer.NoUsablePath else MoveAnswer.Following
            }
        }
    }

    /**
     * Each serve with the connection in [life]: a move waiting for a spare id goes once one came ([holdTurn]), and a
     * move's path is validated ([MoveValidation]).
     */
    fun step(
        now: Nanos,
        life: Life,
    ): PathStep {
        if (holdTurn(life) == HoldTurn.Runs && hold.step(now) == PathStep.NoUsablePath) return PathStep.NoUsablePath
        return validation.step(now)
    }

    /** Offers the time the next challenge, abandonment or probe of a move falls due; offers nothing when settled. */
    fun offerWake(
        sink: WakeSink,
        life: Life,
    ) {
        if (holdTurn(life) == HoldTurn.Runs) hold.offerWake(sink)
        move.offerWake(sink)
    }

    /**
     * Whether a move held waiting may go, or wake the connection, now: not while a landing stands, whose end the move
     * waits for, nor once the connection is closing, when it asks for no socket.
     */
    private fun holdTurn(life: Life): HoldTurn =
        when (move) {
            is Move.Landing -> HoldTurn.Rests
            is Move.Settled, is Move.Unfinished ->
                when (life) {
                    is Life.Running -> HoldTurn.Runs
                    is Life.Closing, is Life.Over -> HoldTurn.Rests
                }
        }

    /** The OS reports another usable network under the socket: the move is followed afresh ([Move.onNewNetwork]). */
    fun networkChanged(now: Nanos) = run { move = move.onNewNetwork(now) }

    /** The loop refused the socket [ticket] names: the move that asked for it ends, and the connection stays where it is. */
    fun refused(ticket: SocketTicket): SecondHeld =
        when (val awaited = move.awaiting(ticket)) {
            is Awaited.Ask -> SecondHeld.Lost.also { move = Move.Settled(awaited.asked.from) }
            is Awaited.Stale -> SecondHeld.Stale
        }

    /**
     * The loop lost the second socket [ticket] names, the connection in [life]: the move it served ends where it
     * started, and a landing on it migrates back to the socket the loop still carries on; once the connection is
     * closing the landing only ends, its close going out of that socket.
     */
    fun lost(
        now: Nanos,
        ticket: SocketTicket,
        life: Life,
    ): SecondLoss =
        when (val holding = move.holding(ticket)) {
            is Holding.Stale -> SecondLoss.Stale
            is Holding.Unfinished -> SecondLoss.MoveEnded.also { move = Move.Settled(costs.abandon(holding.move)) }
            is Holding.Landing ->
                when (life) {
                    is Life.Running ->
                        when (val back = landings.back(now, holding.landing)) {
                            is Back.Home -> SecondLoss.MovedBack.also { move = back.settled }
                            is Back.Refused -> SecondLoss.NoPathBack
                        }
                    is Life.Closing, is Life.Over -> SecondLoss.MoveEnded.also { move = landings.dropped(holding.landing) }
                }
        }

    /** The loop promoted the socket [ticket] names: a landing on it is settled there ([MoveLandings.promoted]). */
    fun promoted(ticket: SocketTicket): Promoted =
        when (val holding = move.holding(ticket)) {
            is Holding.Landing -> Promoted.Settled.also { move = landings.promoted(holding.landing) }
            is Holding.Unfinished, is Holding.Stale -> Promoted.Stale
        }

    /** The connection cannot follow the socket [asked] awaited: the loop closes it, and the move is refused. */
    private fun decline(asked: Move.Asked): MoveAnswer {
        move = Move.Settled(asked.from)
        costs.close(asked.ticket)
        return MoveAnswer.Refused
    }

    /** Whether the path quiche carries on leaves from a validated endpoint ([Move.address]). */
    fun address(): PeerAddress = move.address()

    /** How an idle timeout reads while the move stands ([Move.idleEnd]). */
    fun idleEnd(): ConnEnd = move.idleEnd(hold.held)

    /** Hands [each] every endpoint this connection made, for its release, and frees the paths' own memory. */
    fun made(each: (LocalAddr) -> Unit) {
        costs.release(each)
        move.locals(each)
        events.free()
    }

    /**
     * Whether a move open now already goes where [wanted] says, toward its address on its network: the move waiting for
     * a spare id or a landing to end, else the one last asked while it is unfinished or landing.
     */
    fun serves(wanted: MoveWanted): Served {
        val open =
            when (val held = hold.held) {
                is HeldMove.Waiting -> held.wanted
                is HeldMove.None ->
                    when (move) {
                        is Move.Settled -> return Served.Not
                        is Move.Unfinished, is Move.Landing ->
                            when (val now = toward) {
                                is Toward.Nowhere -> return Served.Not
                                is Toward.To -> now.wanted
                            }
                    }
            }
        return open.serves(wanted)
    }

    /** Asks the loop for the socket a move where [wanted] says leaves from, the connection carrying on [from] until then. */
    private fun ask(
        from: Linked,
        wanted: MoveWanted,
    ): Opening {
        val ticket = costs.nextTicket()
        move = Move.Asked(from, ticket).also { rebinds.opening() }
        toward = Toward.To(wanted)
        costs.open(ticket, wanted)
        return Opening.Asked
    }
}
