@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: every Paths variant lives in one.

package com.ditchoom.socket.quic.core.path

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.MigrateOutcome
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.PeerMigrationPermission
import com.ditchoom.socket.quic.ProbeOutcome
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.connPeerMigrationPermission
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.decodePathKey
import com.ditchoom.socket.quic.encodeToNative
import kotlin.random.Random

/**
 * A client's paths. Its socket has one local endpoint at a time; when the socket moves, the new endpoint's path is
 * probed (RFC 9000 §8.2) with datagrams of at most 1200 bytes, the connection migrates to it once validated, and the
 * id of the path it left is retired in the same step. Datagrams quiche writes for a path whose socket is gone are
 * dropped. Inside the connection only.
 */
@Confined
internal class ClientPaths(
    private val q: QuicheConnRef,
    private val first: SinglePath,
    private val factory: BufferFactory,
    private val ids: IdIssuer,
    private val random: Random,
    private val free: (QuicheRecvInfo, Made) -> Unit,
) {
    private val toPeer = Outgoing.To(first.target)
    private val fixed = Sorting.Fixed(toPeer)
    private var move: Move =
        Move.Settled(
            Linked(LocalAddr(q.api.decodePathKey(first.local.address), first.recvInfo, Made.ByEdge, RttPrior.CONFIGURED), 0L),
        )
    private val events = PathEvents(q, factory)
    private val peer = TowardPeer(q, first)
    private var previous: Previous = Previous.None
    private var heard = Heard(0L, Nanos(0L))
    private var arrived = 0L
    private val retiredIds = RetiredReader(factory)

    val sendInfo get() = first.sendInfo

    fun read(): PathRead = first.read()

    /** What quiche is told about a datagram that arrived: it came on the endpoint [Move.receivesAt] names. */
    fun recvFrom(): RecvFrom = move.receivesAt().info.also { arrived++ }

    fun window(full: Window): Window =
        when (move) {
            is Move.Settled -> full
            is Move.Probing, is Move.Stranded -> full.whileValidating()
        }

    fun sorting(): Sorting =
        when (move) {
            is Move.Settled -> fixed
            is Move.Probing, is Move.Stranded -> Sorting.ByAddress
        }

    /** Where a datagram quiche wrote from the local address at [from] goes: out of the socket, if it is the socket's. */
    fun outgoing(from: Long): Outgoing = if (q.addressIs(from, socket().key)) toPeer else Outgoing.Dropped

    /** Where the socket sends from where it is now, whichever address quiche wrote a datagram for. */
    fun fromSocket(): Outgoing.To = toPeer

    /** The connection is established: the peer is given the spare ids a move needs. */
    fun established(): Issued = ids.issueAll(q, random)

    /** The socket now leaves from [local], its round trip assumed [prior]: its path is probed, an earlier move dropped. */
    fun move(
        now: Nanos,
        local: PathKey,
        prior: RttPrior,
    ): MoveAnswer {
        when (q.api.connPeerMigrationPermission(q.handle)) {
            is PeerMigrationPermission.Permitted -> Unit
            is PeerMigrationPermission.Forbidden, is PeerMigrationPermission.NotYetNegotiated -> return MoveAnswer.Refused
        }
        topUp()
        val hearing = q.hearing(heard, arrived, now)
        heard = heard.observe(arrived, now)
        val sockAddr = local.encodeToNative(factory)
        val at = LocalAddr(q.api.decodePathKey(sockAddr.address), peer.recvInfoAt(sockAddr), Made.Here(sockAddr), prior)
        val from = abandon()
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
     * Reads quiche's path events while a move is being validated, and migrates once it is. A path being validated is
     * asked for a fresh PATH_CHALLENGE each time its [Cadence] falls due and abandoned at its end; a move that found no
     * path is probed again when its [Retry] falls due.
     */
    fun step(now: Nanos): PathStep =
        when (val moving = move) {
            is Move.Probing -> follow(now, moving)
            is Move.Stranded -> reprobe(now, moving)
            is Move.Settled -> PathStep.Steady
        }

    /** Offers the time the next challenge, abandonment or probe of a move falls due; offers nothing when settled. */
    fun offerWake(sink: WakeSink) {
        when (val now = move) {
            is Move.Stranded -> sink.offer(now.retry.due)
            is Move.Probing -> {
                sink.offer(now.cadence.challengeDue)
                sink.offer(now.cadence.abandonAt)
            }
            is Move.Settled -> Unit
        }
    }

    /** The OS reports another usable network under the socket: the move is followed afresh ([Move.onNewNetwork]). */
    fun networkChanged(now: Nanos) = run { move = move.onNewNetwork(now) }

    /** Whether the socket's endpoint has no validated path yet: an idle end then means no usable path was found. */
    fun unsettled(): Boolean = move !is Move.Settled

    /** Hands [each] every endpoint this connection made, for its release, and frees the paths' own memory. */
    fun made(each: (LocalAddr) -> Unit) {
        when (val held = previous) {
            is Previous.Held -> each(held.local)
            is Previous.None -> Unit
        }
        move.locals(each)
        events.free()
        ids.free()
        retiredIds.free()
    }

    /**
     * A stranded endpoint is tried again once its [Retry] falls due: a path that never had an id is probed with a fresh
     * one, one that failed validation is probed on the id it kept, and one that validated but could not be migrated to
     * is migrated to. A response that comes after its validation was abandoned still validates the path: it is migrated
     * to at once.
     */
    private fun reprobe(
        now: Nanos,
        stranded: Move.Stranded,
    ): PathStep {
        if (stranded.why == Unprobed.ValidationFailed && events.verdict(stranded.at.key) == PathVerdict.Validated) {
            return migrate(now, stranded.from, stranded.at, stranded.id, stranded.retry.failed)
        }
        if (now.value < stranded.retry.due.value) return PathStep.Steady
        topUp()
        val hearing = q.hearing(heard, arrived, now)
        heard = heard.observe(arrived, now)
        return when (stranded.why) {
            Unprobed.MigrationRefused -> migrate(now, stranded.from, stranded.at, LinkedId.None, stranded.retry.failed)
            Unprobed.NoSpareId, Unprobed.ValidationFailed -> {
                move =
                    when (val probed = peer.probe(stranded.at)) {
                        is ProbeOutcome.Probed ->
                            Move.Probing(stranded.from, Linked(stranded.at, probed.dcidSeq), stranded.retry.failed, q.cadenceFrom(now))
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
     * A validation quiche has not decided: abandoned once its cadence ends, else asked for a fresh PATH_CHALLENGE when
     * one is due. Probing a path quiche holds only requests validation again, on the id the path already has; a
     * response to any challenge sent on the path validates it (RFC 9000 §8.2.1).
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

    /** The validation of [probing]'s path ended unanswered: the endpoint keeps the path's id and is probed again later. */
    private fun giveUp(
        now: Nanos,
        probing: Move.Probing,
    ): PathStep {
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
                // The old path's socket is gone: what it had in flight is resent from the new one now.
                peer.requeue(from.local)
                retire(from.dcidSeq)
                spend(from.local)
                move = Move.Settled(Linked(to, migrated.dcidSeq))
                PathStep.Migrated
            }
            is MigrateOutcome.Rejected -> {
                when (id) {
                    is LinkedId.Seq -> retire(id.seq)
                    is LinkedId.None -> Unit
                }
                move = Move.Stranded(from, to, Unprobed.MigrationRefused, q.retryAt(now, failed + 1), LinkedId.None)
                PathStep.Steady
            }
        }

    /**
     * Hands the peer the ids it may use for the paths of moves to come: each path uses one, and the peer retires those of
     * the paths it left. What it retired is read out of quiche, which keeps it until it is.
     */
    private fun topUp() {
        retiredIds.read(q)
        ids.issueAll(q, random)
    }

    /** Ends an unfinished move: a probe's id is retired and its endpoint spent. Answers the path data still rides. */
    private fun abandon(): Linked =
        when (val now = move) {
            is Move.Settled -> now.on
            is Move.Probing -> {
                retire(now.to.dcidSeq)
                spend(now.to.local)
                now.from
            }
            is Move.Stranded -> {
                when (val held = now.id) {
                    is LinkedId.Seq -> retire(held.seq)
                    is LinkedId.None -> Unit
                }
                spend(now.at)
                now.from
            }
        }

    /** [local] is left behind: it is kept as the previous endpoint, and the one kept before it is freed. */
    private fun spend(local: LocalAddr) {
        when (val held = previous) {
            is Previous.Held -> free(held.local.recvInfo, held.local.made)
            is Previous.None -> Unit
        }
        previous = Previous.Held(local)
    }

    private fun socket(): LocalAddr = move.socketAt()

    private fun retire(seq: Long) {
        // A refusal leaves the id with quiche until the connection ends; no path uses it.
        q.api.connRetireDcid(q.handle, seq)
    }
}
