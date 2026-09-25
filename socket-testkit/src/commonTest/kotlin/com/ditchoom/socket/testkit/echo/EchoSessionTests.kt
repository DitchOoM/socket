package com.ditchoom.socket.testkit.echo

import com.ditchoom.socket.testkit.walk.LaneWatch
import com.ditchoom.socket.testkit.walk.StallBounds
import com.ditchoom.socket.testkit.walk.WalkTarget
import com.ditchoom.socket.testkit.walk.WalkTargets
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
    fun steppingAnEndedSessionReturnsTheRecordedEnd() {
        val session = EchoSession(connectedAt = Duration.ZERO)
        val t = session.warm()
        session.sent(21, "probe-21;", t)
        assertIs<EchoStep.Reconnect>(session.reply(21, StreamReply.PeerEnded, t + 1.seconds))

        val replyAgain = session.reply(22, StreamReply.PeerReset, t + 2.seconds)
        val writeAgain = session.writeTimedOut(23, waited = 5.seconds, at = t + 7.seconds)

        assertEquals<EchoStep>(
            EchoStep.Reconnect(SessionEnd.StreamGone.PeerEndedStream, seq = 22, owedBytes = 9),
            replyAgain,
            "a later step answers with the end the session recorded, not a new one",
        )
        assertEquals<EchoStep>(EchoStep.Reconnect(SessionEnd.StreamGone.PeerEndedStream, seq = 23, owedBytes = 9), writeAgain)
        assertEquals(21, session.exchanges, "the FIN was read by exchange 21; stepping an ended session is not one")
        assertEquals(SessionEnd.StreamGone.PeerEndedStream, session.close(fallback = SessionEnd.WalkOver, at = t + 8.seconds).end)
    }

    @Test
    fun aSessionEndedFromOutsideAnswersEveryStepWithThatEnd() {
        val session = EchoSession(connectedAt = Duration.ZERO)
        val t = session.warm()
        session.ended(SessionEnd.ConnectionDead, at = t)

        val step = session.reply(21, StreamReply.Echoed("probe-21;"), t + 58.milliseconds)

        val reconnect = assertIs<EchoStep.Reconnect>(step)
        assertEquals<SessionEnd>(SessionEnd.ConnectionDead, reconnect.end)
        assertEquals("SESSION-ENDED seq=21 owed=0B ended=ConnectionDead — the session had already ended; leaving scope", reconnect.line)
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

    /**
     * 46 777 write timeouts, 5.28 s apart, and not one STALL-SUSPECTED in 68 h. Each run of them now
     * leaves the connection and the lane reconnects at once; connections that connect and never
     * complete a read are still an echo loop that is not running.
     */
    @Test
    fun writesThatKeepTimingOutAreAStallTheWatchdogCalls() {
        var t = Duration.ZERO
        val bounds = StallBounds.forConnect(echoSilence = 120.seconds, handshakeBound = 30.seconds, margin = 10.seconds)
        val lane = WalkTargets(WalkTarget("192.0.2.1", 4433), emptyList()).lanes.single()
        val watch = LaneWatch(lane, bounds, clock = { t }, startsAfter = Duration.ZERO)
        watch.nextAttempt()
        watch.connected()
        var session = EchoSession(connectedAt = Duration.ZERO)
        var nextBeat = 60.seconds
        var seq = 0
        val beats = ArrayList<LaneWatch.Beat>()
        while (t < 250.seconds) {
            seq++
            when (session.writeTimedOut(seq, waited = 5.seconds, at = t + 5.seconds)) {
                is EchoStep.Exchanged, is EchoStep.WriteTimedOut -> Unit
                is EchoStep.Reconnect -> {
                    watch.backingOff(3.seconds)
                    watch.nextAttempt()
                    watch.connected()
                    session = EchoSession(connectedAt = t)
                }
            }
            watch.progressed(session.exchanges)
            t += 5280.milliseconds
            if (t >= nextBeat) {
                beats += watch.beat()
                nextBeat += 60.seconds
            }
        }

        assertEquals(0, watch.loopTicks, "a write that never completed is not progress")
        val stalled = assertIs<LaneWatch.Beat.Stalled>(beats[2], "overdue at the ~120 s beat, called at the next: $beats")
        assertEquals(
            "STALL-SUSPECTED phase=Echoing attempt=3 loopTicks=0 waited=184s bound=120s — expected loopTicks to advance; " +
                "the echo loop is not running. Dumping the last 256 trace events.",
            stalled.line(ringSize = 256),
        )
        assertEquals(LaneWatch.Beat.StillStalled, beats[3], "a reconnect that reads nothing is the same stall, not a recovery")
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
