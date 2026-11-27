@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: a move's path is validated here.

package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.MigrateOutcome
import com.ditchoom.socket.quic.ProbeOutcome
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos

/**
 * Where a client's move stands, and whether its carrying path answered since the move's last validation began: held
 * once, for the client's paths and the validation of their moves.
 */
@Confined
internal class MoveState(
    first: Move,
) {
    var move: Move = first
        private set
    var carryingHeard = Answered.Not
        private set

    /** The move stands at [next] from now on. */
    fun stands(next: Move) {
        move = next
    }

    /** The carrying path answered. */
    fun heard() {
        carryingHeard = Answered.Yes
    }

    /** A validation begins: the carrying path has not answered through it yet. */
    fun validating() {
        carryingHeard = Answered.Not
    }
}

/**
 * The validation of a client's move, read from quiche's path events each serve: a fresh PATH_CHALLENGE each time its
 * [Cadence] falls due, abandoned at its end; a move that found no path is probed again when its [Retry] falls due; once
 * the path validates quiche migrates to it, and the loop is asked to promote its socket ([Move.Landing]). Inside the
 * connection only.
 */
@Confined
internal class MoveValidation(
    private val q: QuicheConnRef,
    private val state: MoveState,
    private val events: PathEvents,
    private val peer: TowardPeer,
    private val ears: PeerHearing,
    private val costs: MoveCosts,
    private val silence: PathSilence,
) {
    fun step(now: Nanos): PathStep =
        when (val moving = state.move) {
            is Move.Probing -> follow(now, moving)
            is Move.Stranded -> reprobe(now, moving)
            is Move.Settled, is Move.Asked, is Move.Landing -> PathStep.Steady
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
        if (stranded.retry.failed >= ABANDON_AFTER_FAILURES && state.carryingHeard == Answered.Yes) {
            state.stands(Move.Settled(costs.abandon(stranded)))
            return PathStep.Abandoned
        }
        costs.topUp()
        val hearing = ears.judge(now)
        return when (stranded.why) {
            Unprobed.MigrationRefused -> migrate(now, stranded.from, stranded.at, LinkedId.None, stranded.retry.failed)
            Unprobed.NoSpareId, Unprobed.ValidationFailed -> {
                when (val probed = peer.probe(stranded.at)) {
                    is ProbeOutcome.Probed -> {
                        state.validating()
                        val linked = Linked(stranded.at, probed.dcidSeq)
                        state.stands(Move.Probing(stranded.from, linked, stranded.retry.failed, q.cadenceFrom(now)))
                    }
                    is ProbeOutcome.Rejected -> {
                        if (stranded.why == Unprobed.NoSpareId && probed.endsMove(hearing)) return PathStep.NoUsablePath
                        state.stands(stranded.again(q, now))
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

    /** A validation quiche has not decided: abandoned at its cadence's end, else challenged afresh when due (RFC 9000 §8.2.1). */
    private fun tend(
        now: Nanos,
        probing: Move.Probing,
    ): PathStep {
        if (now.value >= probing.cadence.abandonAt.value) return giveUp(now, probing)
        if (now.value < probing.cadence.challengeDue.value) return PathStep.Steady
        return when (peer.probe(probing.to.local)) {
            is ProbeOutcome.Probed -> {
                state.stands(Move.Probing(probing.from, probing.to, probing.failed, probing.cadence.challenged(now)))
                PathStep.Challenged
            }
            is ProbeOutcome.Rejected -> giveUp(now, probing)
        }
    }

    /**
     * The validation of [probing]'s path ended unanswered: the endpoint keeps the path's id and is probed again later, or,
     * the [ABANDON_AFTER_FAILURES]th time with the carrying path answering through it, the move is given up ([MoveCosts.dropSecond]).
     */
    private fun giveUp(
        now: Nanos,
        probing: Move.Probing,
    ): PathStep {
        if (probing.failed + 1 >= ABANDON_AFTER_FAILURES && state.carryingHeard == Answered.Yes) {
            state.stands(Move.Settled(costs.abandon(probing)))
            return PathStep.Abandoned
        }
        val retry = validationRetryAt(now, probing.failed + 1)
        state.stands(Move.Stranded(probing.from, probing.to.local, Unprobed.ValidationFailed, retry, LinkedId.Seq(probing.to.dcidSeq)))
        return PathStep.ProbeFailed
    }

    /** quiche migrates to [to]'s validated path, and the loop is asked to promote its socket; one it refuses is retried. */
    private fun migrate(
        now: Nanos,
        from: Linked,
        to: LocalAddr,
        id: LinkedId,
        failed: Int,
    ): PathStep =
        when (val migrated = peer.migrate(to)) {
            is MigrateOutcome.Migrated -> {
                costs.promote(to.socket)
                state.stands(Move.Landing(from, Linked(to, migrated.dcidSeq)))
                silence.migrated(now)
                PathStep.Migrated
            }
            is MigrateOutcome.Rejected -> {
                q.retire(id)
                state.stands(Move.Stranded(from, to, Unprobed.MigrationRefused, q.retryAt(now, failed + 1), LinkedId.None))
                PathStep.Steady
            }
        }
}
