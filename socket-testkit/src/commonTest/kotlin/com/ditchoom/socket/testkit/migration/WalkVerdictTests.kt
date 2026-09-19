package com.ditchoom.socket.testkit.migration

import com.ditchoom.socket.testkit.echo.EchoLivenessTotals
import com.ditchoom.socket.testkit.echo.EchoLivenessVerdict
import com.ditchoom.socket.testkit.echo.EchoSession
import com.ditchoom.socket.testkit.echo.EchoStep
import com.ditchoom.socket.testkit.echo.SessionEnd
import com.ditchoom.socket.testkit.echo.StreamReply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Samsung, 2026-09-12 walk, connection 1: eight migrations succeeded, two probes went unanswered and
 * four later ones were answered, and the stream carried no answered echo for the last 73 h of 75.
 * The path layer's verdict for that connection was `PASS`.
 */
class WalkVerdictTests {
    private val samsungConnectionOne =
        PoolProbeHistory(
            attempts = 10,
            unanswered = 2,
            probedAfterUnanswered = 5,
            answeredAfterUnanswered = 4,
            succeededAfterUnanswered = 4,
        ).verdict()

    private fun twentyEchoes(): EchoSession {
        val session = EchoSession(connectedAt = Duration.ZERO)
        var t = Duration.ZERO
        repeat(20) { i ->
            session.sent(i + 1, "probe-${i + 1};", t)
            assertIs<EchoStep.Exchanged.Judged>(session.reply(i + 1, StreamReply.Echoed("probe-${i + 1};"), t + 58.milliseconds))
            t += 250.milliseconds
        }
        return session
    }

    @Test
    fun eightMigrationsAndNoAnsweredEchoForHoursIsNotAPass() {
        assertTrue(samsungConnectionOne.line.startsWith("PASS"), "the path layer alone reads PASS: ${samsungConnectionOne.line}")
        val report = twentyEchoes().close(SessionEnd.WalkOver, at = 75.hours)
        assertIs<EchoLivenessVerdict.Silent>(report.liveness)

        val connection = samsungConnectionOne.forConnection(report.liveness)
        val run = samsungConnectionOne.forRun(connections = 1, liveness = EchoLivenessTotals().apply { absorb(1, report) }.verdict())

        assertIs<ConnectionVerdict.Silenced>(connection, "a connection that echoed nothing for 73 h cannot pass: ${connection.line}")
        assertTrue(connection.line.startsWith("FAIL — no answered echo for 74h"), connection.line)
        assertIs<RunVerdict.Silenced>(run, run.line)
        assertTrue(run.line.startsWith("FAIL — connection 1 went 74h"), run.line)
        assertTrue(run.line.contains("on its own it read: PASS"), "the path verdict is still reported, as void: ${run.line}")
    }

    @Test
    fun aLiveConnectionKeepsThePathLayersVerdict() {
        val report = twentyEchoes().close(SessionEnd.ConnectionDead, at = 5.seconds)
        assertIs<EchoLivenessVerdict.Live>(report.liveness)

        val connection = samsungConnectionOne.forConnection(report.liveness)
        val run = samsungConnectionOne.forRun(connections = 1, liveness = EchoLivenessTotals().apply { absorb(1, report) }.verdict())

        assertEquals(ConnectionVerdict.Standing(samsungConnectionOne), connection)
        assertEquals(samsungConnectionOne.line, connection.line)
        assertEquals(samsungConnectionOne.runLine(1), run.line)
    }

    @Test
    fun aRunThatNeverConnectedKeepsThePathLayersVerdict() {
        val pool = PoolProbeHistory().verdict()

        val run = pool.forRun(connections = 0, liveness = EchoLivenessTotals().verdict())

        assertEquals(RunVerdict.Standing(pool, 0), run)
    }
}
