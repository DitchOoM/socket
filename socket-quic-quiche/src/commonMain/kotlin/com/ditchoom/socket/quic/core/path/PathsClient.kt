@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: every Paths variant lives in one.

package com.ditchoom.socket.quic.core.path

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.MigrateOutcome
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.PeerMigrationPermission
import com.ditchoom.socket.quic.ProbeOutcome
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.connPeerMigrationPermission
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.decodePathKey
import com.ditchoom.socket.quic.encodeToNative
import kotlin.random.Random

/**
 * A client's paths. A move is made before it breaks: the new endpoint's socket opens beside the carrying one and its path
 * is probed (RFC 9000 §8.2) with datagrams of at most 1200 bytes; once it validates, the connection migrates and the loop
 * promotes the socket. A move refused, lost or failing while the old path answers ends with the connection where it was.
 */
@Confined
internal class ClientPaths(
    private val q: QuicheConnRef,
    private val first: SinglePath,
    private val factory: BufferFactory,
    private val ids: IdIssuer,
    private val random: Random,
    private val free: (QuicheRecvInfo, Made) -> Unit,
    private val ask: (SocketAsk) -> Unit,
    val silence: PathSilence,
) {
    private var move: Move = q.firstMove(first)

    /** Whether the carrying path answered since the move's last validation began. */
    private var carryingHeard = Answered.Not
    private val events = PathEvents(q, factory)
    private val peer = TowardPeer(q, first)
    private var previous: Previous = Previous.None
    private val ears = PeerHearing(q)
    private val retiredIds = RetiredReader(factory)

    /** Where the socket is wanted, and whether it waits to move there; a new move gives up the one under way first. */
    val hold =
        MoveHold(q, ears) { asked ->
            // Before the new socket's open is asked, so no promotion of the old probe can follow it to the loop.
            if (asked is SocketAsk.OpenBeside) move = Move.Settled(abandon())
            ask(asked)
        }

    val sendInfo get() = first.sendInfo

    fun read(): PathRead = first.read()

    fun stats(): QuicStatsRead = first.stats()

    /** What quiche is told about a datagram that arrived on socket [on]: on the endpoint [Move.readsOnSecond] chooses. */
    fun recvFrom(on: SocketTicket): RecvFrom {
        ears.arrived()
        if (move.readOn(on) == ReadOn.Second) return move.second().info
        if (move.carrying().socket == on) carryingHeard = Answered.Yes
        return move.carrying().info
    }

    fun window(full: Window): Window = move.window(full)

    fun sorting(): Sorting = move.sorting()

    /** Where a datagram quiche wrote from the local address at [from] goes: out of the socket that holds that address. */
    fun outgoing(from: Long): Outgoing = move.outgoing(q, from)

    /** Where the carrying socket sends, whichever address quiche wrote a datagram for. */
    fun fromSocket(): Outgoing.To = move.carrying().out

    /** The connection is established: the peer is given the spare ids a move needs. */
    fun established(): Issued = ids.issueAll(q, random)

    /** The loop's second socket [ticket] leaves from [local], assumed [prior]: its path is probed, an earlier move dropped. */
    fun move(
        now: Nanos,
        local: PathKey,
        prior: RttPrior,
        ticket: SocketTicket,
    ): MoveAnswer {
        when (q.api.connPeerMigrationPermission(q.handle)) {
            is PeerMigrationPermission.Permitted -> Unit
            is PeerMigrationPermission.Forbidden, is PeerMigrationPermission.NotYetNegotiated -> return decline(ticket)
        }
        topUp()
        val hearing = ears.judge(now)
        val sockAddr = local.encodeToNative(factory)
        val at =
            LocalAddr(q.api.decodePathKey(sockAddr.address), peer.recvInfoAt(sockAddr), Made.Here(sockAddr), prior, ticket, first.target)
        val from = abandon()
        carryingHeard = Answered.Not
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
     * Reads quiche's path events while a move is validated, and migrates once it is: a fresh PATH_CHALLENGE each time its
     * [Cadence] falls due, abandoned at its end; a move that found no path is probed again when its [Retry] falls due.
     */
    fun step(now: Nanos): PathStep {
        if (hold.step(now) == PathStep.NoUsablePath) return PathStep.NoUsablePath
        return when (val moving = move) {
            is Move.Probing -> follow(now, moving)
            is Move.Stranded -> reprobe(now, moving)
            is Move.Settled -> PathStep.Steady
        }
    }

    /** Offers the time the next challenge, abandonment or probe of a move falls due; offers nothing when settled. */
    fun offerWake(sink: WakeSink) {
        hold.offerWake(sink)
        move.offerWake(sink)
    }

    /** The OS reports another usable network under the socket: the move is followed afresh ([Move.onNewNetwork]). */
    fun networkChanged(now: Nanos) = run { move = move.onNewNetwork(now) }

    /** The connection cannot follow the second socket [ticket] names: the loop closes it, and the move is refused. */
    fun decline(ticket: SocketTicket): MoveAnswer = MoveAnswer.Refused.also { ask(SocketAsk.CloseSecond(ticket)) }

    /** The loop lost the second socket [ticket] names: the move it served ends, and the connection stays where it is. */
    fun lost(ticket: SocketTicket): SecondHeld {
        if (move is Move.Settled || move.second().socket != ticket) return SecondHeld.Stale
        move = Move.Settled(abandon())
        return SecondHeld.Lost
    }

    /** Whether the socket's endpoint has no validated path yet: an idle end then means no usable path was found. */
    fun unsettled(): Boolean = move !is Move.Settled || hold.held is HeldMove.Waiting

    /** Hands [each] every endpoint this connection made, for its release, and frees the paths' own memory. */
    fun made(each: (LocalAddr) -> Unit) {
        previous.held(each)
        move.locals(each)
        events.free()
        ids.free()
        retiredIds.free()
    }

    /**
     * A stranded endpoint is tried again once its [Retry] falls due: one that never had an id is probed with a fresh one,
     * one that failed validation on the id it kept, one that validated but could not be migrated to is migrated to; a
     * response after its validation was abandoned still validates the path, migrated to at once.
     */
    private fun reprobe(
        now: Nanos,
        stranded: Move.Stranded,
    ): PathStep {
        if (stranded.why == Unprobed.ValidationFailed && events.verdict(stranded.at.key) == PathVerdict.Validated) {
            return migrate(now, stranded.from, stranded.at, stranded.id, stranded.retry.failed)
        }
        if (now.value < stranded.retry.due.value) return PathStep.Steady
        if (stranded.retry.failed >= ABANDON_AFTER_FAILURES && carryingHeard == Answered.Yes) {
            move = Move.Settled(abandon())
            return PathStep.Abandoned
        }
        topUp()
        val hearing = ears.judge(now)
        return when (stranded.why) {
            Unprobed.MigrationRefused -> migrate(now, stranded.from, stranded.at, LinkedId.None, stranded.retry.failed)
            Unprobed.NoSpareId, Unprobed.ValidationFailed -> {
                move =
                    when (val probed = peer.probe(stranded.at)) {
                        is ProbeOutcome.Probed -> {
                            carryingHeard = Answered.Not
                            Move.Probing(stranded.from, Linked(stranded.at, probed.dcidSeq), stranded.retry.failed, q.cadenceFrom(now))
                        }
                        is ProbeOutcome.Rejected -> {
                            if (stranded.why == Unprobed.NoSpareId && probed.endsMove(hearing)) return PathStep.NoUsablePath
                            stranded.again(q, now)
                        }
                    }
                PathStep.Reprobed
            }
        }
    }

    private fun follow(
        now: Nanos,
        probing: Move.Probing,
    ): PathStep =
        when (events.verdict(probing.to.local.key)) {
            PathVerdict.Validated -> migrate(now, probing.from, probing.to.local, LinkedId.Seq(probing.to.dcidSeq), probing.failed)
            PathVerdict.Failed -> giveUp(now, probing)
            PathVerdict.Undecided -> tend(now, probing)
            PathVerdict.Unreadable -> PathStep.Steady
        }

    /**
     * A validation quiche has not decided: abandoned once its cadence ends, else asked for a fresh PATH_CHALLENGE when due,
     * on the id the path already has; a response to any challenge sent on the path validates it (RFC 9000 §8.2.1).
     */
    private fun tend(
        now: Nanos,
        probing: Move.Probing,
    ): PathStep {
        if (now.value >= probing.cadence.abandonAt.value) return giveUp(now, probing)
        if (now.value < probing.cadence.challengeDue.value) return PathStep.Steady
        return when (peer.probe(probing.to.local)) {
            is ProbeOutcome.Probed -> {
                move = Move.Probing(probing.from, probing.to, probing.failed, probing.cadence.challenged(now))
                PathStep.Challenged
            }
            is ProbeOutcome.Rejected -> giveUp(now, probing)
        }
    }

    /**
     * The validation of [probing]'s path ended unanswered: the endpoint keeps the path's id and is probed again later, or,
     * the [ABANDON_AFTER_FAILURES]th time with the carrying path answering through it, the move is given up ([dropSecond]).
     */
    private fun giveUp(
        now: Nanos,
        probing: Move.Probing,
    ): PathStep {
        if (probing.failed + 1 >= ABANDON_AFTER_FAILURES && carryingHeard == Answered.Yes) {
            move = Move.Settled(abandon())
            return PathStep.Abandoned
        }
        val retry = validationRetryAt(now, probing.failed + 1)
        move = Move.Stranded(probing.from, probing.to.local, Unprobed.ValidationFailed, retry, LinkedId.Seq(probing.to.dcidSeq))
        return PathStep.ProbeFailed
    }

    private fun migrate(
        now: Nanos,
        from: Linked,
        to: LocalAddr,
        id: LinkedId,
        failed: Int,
    ): PathStep =
        when (val migrated = peer.migrate(to)) {
            is MigrateOutcome.Migrated -> {
                ask(SocketAsk.Promote(to.socket))
                // The old path's socket closes with the promotion: what it had in flight is resent from the new one now.
                peer.requeue(from.local)
                q.retire(from.dcidSeq)
                spend(from.local)
                move = Move.Settled(Linked(to, migrated.dcidSeq))
                PathStep.Migrated
            }
            is MigrateOutcome.Rejected -> {
                q.retire(id)
                move = Move.Stranded(from, to, Unprobed.MigrationRefused, q.retryAt(now, failed + 1), LinkedId.None)
                PathStep.Steady
            }
        }

    /** Hands the peer the ids the paths of moves to come use, one each, after reading out of quiche those it retired. */
    private fun topUp() {
        retiredIds.read(q)
        ids.issueAll(q, random)
    }

    /** Ends an unfinished move ([dropSecond]); answers the path data still rides. */
    private fun abandon(): Linked =
        when (val now = move) {
            is Move.Settled -> now.on
            is Move.Probing -> now.from.also { dropSecond(now.to.local, LinkedId.Seq(now.to.dcidSeq)) }
            is Move.Stranded -> now.from.also { dropSecond(now.at, now.id) }
        }

    /** The second endpoint [local] is left: its path's id retired, its socket closed (once the loop's own close: a no-op), spent. */
    private fun dropSecond(
        local: LocalAddr,
        id: LinkedId,
    ) {
        q.retire(id)
        ask(SocketAsk.CloseSecond(local.socket))
        spend(local)
    }

    /** [local] is left behind: it is kept as the previous endpoint, and the one kept before it is freed. */
    private fun spend(local: LocalAddr) {
        previous.held { left -> free(left.recvInfo, left.made) }
        previous = Previous.Held(local)
    }
}
