package com.ditchoom.socket.testkit.echo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Every case is a shape the 2026-09-12 walk produced (#620): the echo server FIN'd the stream after a
 * 30 s radio gap, the probe read the FIN as a per-echo miss and kept writing, the write blocked at
 * the stream window and timed out every 5 s for 68 h without a line, and the verdict printed PASS.
 */
class EchoSessionTests {
    private val interval = 250.milliseconds

    /** Twenty healthy exchanges at the walk's cadence, ending at the returned time. */
    private fun EchoSession.warm(from: Duration = Duration.ZERO): Duration {
        var t = from
        repeat(20) { i ->
            sent(i + 1, "probe-${i + 1};", t)
            assertIs<EchoStep.Exchanged.Judged>(reply(i + 1, StreamReply.Echoed("probe-${i + 1};"), t + 58.milliseconds))
            t += interval
        }
        return t
    }

    @Test
    fun aPeerFinIsTerminalAndTheSessionLeavesToReconnect() {
        val session = EchoSession(connectedAt = Duration.ZERO)
        val t = session.warm()
        session.sent(21, "probe-21;", t)

        val step = session.reply(21, StreamReply.PeerEnded, t + 1518.milliseconds)

        val reconnect = assertIs<EchoStep.Reconnect>(step, "a FIN is the peer saying it stopped echoing, not a missed echo")
        assertEquals(SessionEnd.StreamGone.PeerEndedStream, reconnect.end)
        assertEquals("STREAM-ENDED-BY-PEER seq=21 owed=9B — the peer stopped echoing; leaving scope to reconnect", reconnect.line)
        val report = session.close(fallback = SessionEnd.WalkOver, at = t + 2.seconds)
        assertEquals(SessionEnd.StreamGone.PeerEndedStream, report.end, "the ledger keeps a FIN apart from a walk that simply ended")
        assertTrue(report.lines("connection=1").last().endsWith("ended=PeerEndedStream"), report.lines("connection=1").toString())
    }

    @Test
    fun aPeerResetIsTerminalToo() {
        val session = EchoSession(connectedAt = Duration.ZERO)
        val t = session.warm()
        session.sent(21, "probe-21;", t)

        val step = session.reply(21, StreamReply.PeerReset, t + 5.milliseconds)

        val reconnect = assertIs<EchoStep.Reconnect>(step)
        assertEquals(SessionEnd.StreamGone.PeerResetStream, reconnect.end)
        assertTrue(reconnect.line.startsWith("STREAM-RESET-BY-PEER seq=21 owed=9B"), reconnect.line)
    }

    @Test
    fun aWriteThatTimesOutIsLoggedAndIsNotAnExchange() {
        val session = EchoSession(connectedAt = Duration.ZERO)
        val t = session.warm()

        val step = session.writeTimedOut(21, waited = 5.seconds, at = t + 5.seconds)

        val timedOut = assertIs<EchoStep.WriteTimedOut>(step, "a timed-out write is an event of its own, not silence")
        assertEquals("ECHO-WRITE-TIMEOUT seq=21 waited=5000ms consecutive=1", timedOut.line)
        assertEquals(20, session.exchanges, "the loop did not go round")
    }

    /** 46 777 write timeouts, 5.28 s apart, and not one STALL-SUSPECTED in 68 h. */
    @Test
    fun writesThatKeepTimingOutAreAStallTheWatchdogCalls() {
        val watchdog = SilenceWatchdog(quietBeatsBeforeAlarm = 2, beatInterval = 60.seconds)
        var session = EchoSession(connectedAt = Duration.ZERO)
        var ticksBeforeThisConnection = 0
        var ticks = 0
        var t = Duration.ZERO
        var nextBeat = 60.seconds
        var seq = 0
        val beats = ArrayList<SilenceWatchdog.Beat>()
        while (t < 200.seconds) {
            seq++
            when (session.writeTimedOut(seq, waited = 5.seconds, at = t + 5.seconds)) {
                is EchoStep.Exchanged, is EchoStep.WriteTimedOut -> Unit
                is EchoStep.Reconnect -> {
                    ticksBeforeThisConnection += session.exchanges
                    session = EchoSession(connectedAt = t)
                }
            }
            ticks = ticksBeforeThisConnection + session.exchanges
            t += 5280.milliseconds
            if (t >= nextBeat) {
                beats += watchdog.beat(ticks)
                nextBeat += 60.seconds
            }
        }

        assertEquals(0, ticks, "a write that never completed is not progress")
        val stalled = assertIs<SilenceWatchdog.Beat.Stalled>(beats[2], "after two quiet heartbeats the watchdog calls it: $beats")
        assertEquals(
            "STALL-SUSPECTED loopTicks=0 unchanged for 120s attempt=1 — the echo loop is not running. Dumping the last 256 trace events.",
            stalled.line(attempt = 1, ringSize = 256),
        )
    }

    @Test
    fun aRunOfWriteTimeoutsLeavesTheConnection() {
        val session = EchoSession(connectedAt = Duration.ZERO)
        val t = session.warm()
        var step: EchoStep = EchoStep.Exchanged.StillOwed
        repeat(EchoSession.WRITE_TIMEOUT_STREAK_LIMIT) { i ->
            step = session.writeTimedOut(21, waited = 5.seconds, at = t + 5.seconds * (i + 1))
        }

        val reconnect = assertIs<EchoStep.Reconnect>(step)
        assertEquals(SessionEnd.StreamGone.WritesStalled(EchoSession.WRITE_TIMEOUT_STREAK_LIMIT), reconnect.end)
        assertTrue(reconnect.line.startsWith("STREAM-WRITES-STALLED seq=21 consecutiveTimeouts=12"), reconnect.line)
    }

    @Test
    fun theWatchdogReportsARecoveryOnce() {
        val watchdog = SilenceWatchdog(quietBeatsBeforeAlarm = 2, beatInterval = 60.seconds)
        assertEquals(SilenceWatchdog.Beat.Progressing, watchdog.beat(5))
        assertEquals(SilenceWatchdog.Beat.Quiet(1), watchdog.beat(5))
        assertIs<SilenceWatchdog.Beat.Stalled>(watchdog.beat(5))
        assertEquals(SilenceWatchdog.Beat.Quiet(3), watchdog.beat(5), "a stall is dumped once, not every minute")
        val recovered = assertIs<SilenceWatchdog.Beat.Recovered>(watchdog.beat(6))
        assertEquals("STALL-RECOVERED loopTicks=6 after 3 quiet heartbeat(s)", recovered.line)
    }

    /** Samsung, connection 1: last answered echo at t+5627 s, walk over at t+270003 s. */
    @Test
    fun aConnectionThatAnsweredNothingForHoursIsSilent() {
        val session = EchoSession(connectedAt = Duration.ZERO)
        val t = session.warm(from = 5622.seconds)

        val report = session.close(fallback = SessionEnd.WalkOver, at = 270_003.seconds)

        val silent = assertIs<EchoLivenessVerdict.Silent>(report.liveness)
        assertEquals(20, silent.answered)
        assertEquals(t - interval + 58.milliseconds, silent.longestQuiet.from)
        assertTrue(silent.longestQuiet.length > 73.hours, "${silent.longestQuiet}")
        val line = report.lines("connection=1").last()
        assertTrue(line.startsWith("ECHO-LIVENESS connection=1 SILENT — answered=20 longestQuietMs="), line)
        assertTrue(line.endsWith("ended=WalkOver"), line)
    }

    @Test
    fun aQuietSpellInsideTheLimitIsLive() {
        val session = EchoSession(connectedAt = Duration.ZERO)
        val t = session.warm()
        session.sent(21, "probe-21;", t)
        session.reply(21, StreamReply.Echoed("probe-21;"), t + 9.minutes)

        val report = session.close(fallback = SessionEnd.ConnectionDead, at = t + 9.minutes + 1.seconds)

        val live = assertIs<EchoLivenessVerdict.Live>(report.liveness)
        assertEquals(9.minutes + interval - 58.milliseconds, live.longestQuiet.length)
        assertTrue(report.lines("connection=3").last().startsWith("ECHO-LIVENESS connection=3 LIVE — answered=21"))
    }

    @Test
    fun aConnectionThatNeverAnsweredMeasuresItsWholeLife() {
        val session = EchoSession(connectedAt = 100.seconds)
        session.sent(1, "probe-1;", 100.seconds)

        val report = session.close(fallback = SessionEnd.ConnectionDead, at = 130.seconds)

        assertEquals(QuietGap(from = 100.seconds, length = 30.seconds), report.liveness.longestQuiet)
        assertEquals(EchoUnanswered.Some(count = 1, firstSeq = 1, lastSeq = 1), report.unanswered)
    }

    @Test
    fun theRunIsAsSilentAsItsWorstConnection() {
        val totals = EchoLivenessTotals()
        val quiet = EchoSession(connectedAt = Duration.ZERO)
        quiet.warm()
        totals.absorb(1, quiet.close(SessionEnd.StreamGone.PeerEndedStream, at = 13.8.hours))
        val fine = EchoSession(connectedAt = 14.hours)
        fine.warm(from = 14.hours)
        totals.absorb(2, fine.close(SessionEnd.ConnectionDead, at = 14.hours + 20.seconds))

        val run = assertIs<RunLiveness.Silent>(totals.verdict())

        assertEquals(1, run.worstConnection)
        assertEquals(40, run.answered)
        assertEquals(mapOf("PeerEndedStream" to 1, "ConnectionDead" to 1), run.endings)
        assertTrue(run.line.startsWith("ECHO-LIVENESS run SILENT — connection 1 went 13h 4"), run.line)
    }
}
