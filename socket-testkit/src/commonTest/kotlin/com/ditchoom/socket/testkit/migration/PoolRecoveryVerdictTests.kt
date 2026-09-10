package com.ditchoom.socket.testkit.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every case here is a history a real walk produced, and the first two are why this file exists: the
 * probe reported `PASS — … the pool recovered in the field` for a connection whose every probe went
 * unanswered and which then died of an idle timeout. A verdict that cannot distinguish recovery from
 * a run of failures makes the whole walk unreadable, so the distinction is pinned here rather than in
 * a comment.
 */
class PoolRecoveryVerdictTests {
    /** iOS walk 2026-09-10, connection 1: three unanswered probes, nothing answered, then dead. */
    @Test
    fun aRunOfUnansweredProbesIsNotRecovery() {
        val verdict =
            PoolProbeHistory(
                attempts = 3,
                unanswered = 3,
                probedAfterUnanswered = 2,
            ).verdict()

        assertEquals(PoolRecoveryVerdict.NeverRecovered(unanswered = 3, probedAfter = 2), verdict)
        assertTrue(
            verdict.line.startsWith("FAIL"),
            "a connection that never regained a path must not read as a pass: ${verdict.line}",
        )
    }

    /** iOS walk 2026-09-10, connection 2: one migration succeeded 27 minutes BEFORE the failures. */
    @Test
    fun aSuccessBeforeTheFirstLostProbeSaysNothingAboutAfterIt() {
        val verdict =
            PoolProbeHistory(
                attempts = 4,
                unanswered = 3,
                probedAfterUnanswered = 2,
            ).verdict()

        assertTrue(
            verdict is PoolRecoveryVerdict.NeverRecovered,
            "the pool's state before a probe was lost is not evidence about after: ${verdict.line}",
        )
    }

    /** iOS walk 2026-09-06 evening: a probe was answered 1.9 s after one went unanswered. */
    @Test
    fun anAnsweredProbeAfterALostOneIsTheRecovery() {
        val verdict =
            PoolProbeHistory(
                attempts = 6,
                unanswered = 4,
                probedAfterUnanswered = 5,
                answeredAfterUnanswered = 2,
                succeededAfterUnanswered = 2,
            ).verdict()

        assertEquals(
            PoolRecoveryVerdict.Recovered(unanswered = 4, answeredAfter = 2, succeededAfter = 2),
            verdict,
        )
        assertTrue(verdict.line.startsWith("PASS"), verdict.line)
    }

    @Test
    fun aRefusalForWantOfACidAfterALostProbeIsTheRegressionItNames() {
        val verdict =
            PoolProbeHistory(
                attempts = 7,
                unanswered = 3,
                probedAfterUnanswered = 4,
                noSpareAfterUnanswered = 4,
            ).verdict()

        assertEquals(PoolRecoveryVerdict.Exhausted(unanswered = 3, noSpareAfter = 4), verdict)
        assertTrue(verdict.line.startsWith("REGRESSION"), verdict.line)
    }

    /** A pool that refused a CID and then handed one out has, by observation, come back. */
    @Test
    fun anAnsweredProbeOutranksAnEarlierRefusal() {
        val verdict =
            PoolProbeHistory(
                attempts = 8,
                unanswered = 2,
                probedAfterUnanswered = 5,
                answeredAfterUnanswered = 1,
                succeededAfterUnanswered = 1,
                noSpareAfterUnanswered = 3,
            ).verdict()

        assertTrue(verdict is PoolRecoveryVerdict.Recovered, verdict.line)
    }

    @Test
    fun aCleanConnectionExercisesOnly445() {
        val verdict = PoolProbeHistory(attempts = 2).verdict()

        assertEquals(PoolRecoveryVerdict.NeverLostAProbe(attempts = 2), verdict)
        assertTrue(verdict.line.startsWith("INCONCLUSIVE"), verdict.line)
    }

    @Test
    fun aLostProbeWithNoLaterAttemptNeverPutsTheQuestion() {
        val verdict = PoolProbeHistory(attempts = 1, unanswered = 1).verdict()

        assertEquals(PoolRecoveryVerdict.NeverRetried(unanswered = 1), verdict)
        assertTrue(verdict.line.startsWith("INCONCLUSIVE"), verdict.line)
    }

    /** The end-of-walk line an operator reads: it must fail the same histories the per-connection one does. */
    @Test
    fun theRunRollUpRefusesTheSameFalsePass() {
        val history = PoolProbeHistory(attempts = 7, unanswered = 6, probedAfterUnanswered = 4)

        assertTrue(
            history.verdict().runLine(connections = 3).startsWith("FAIL"),
            "the walk summary is the line that gets believed; it must not pass a run that never recovered",
        )
        assertTrue(
            PoolProbeHistory(attempts = 3, unanswered = 1, probedAfterUnanswered = 2, answeredAfterUnanswered = 1)
                .verdict()
                .runLine(connections = 2)
                .startsWith("PASS"),
        )
    }
}
