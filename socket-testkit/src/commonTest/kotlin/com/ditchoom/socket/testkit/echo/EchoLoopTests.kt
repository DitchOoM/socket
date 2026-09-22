package com.ditchoom.socket.testkit.echo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The walk probes' [EchoLoop] against a peer whose echo times are scripted, on virtual time, so every
 * round trip the loop reports can be checked against the one the stream actually had.
 */
class EchoLoopTests {
    private val interval = 250.milliseconds
    private val trueRoundTrip = 40.milliseconds

    /** One judged echo, with the read deadline the session held right after judging it. */
    private data class Judged(
        val seq: Int,
        val roundTrip: Duration,
        val owedBytes: Int,
        val readDeadline: Duration,
    ) {
        override fun toString(): String =
            "seq=$seq rtt=${roundTrip.inWholeMilliseconds}ms owed=${owedBytes}B deadline=${readDeadline.inWholeMilliseconds}ms"
    }

    private class Run(
        val judged: List<Judged>,
        val lines: List<String>,
    )

    private suspend fun kotlinx.coroutines.test.TestScope.walk(
        echoAfter: (write: Int) -> Duration,
        exchanges: Int,
    ): Run {
        val clock = { testScheduler.currentTime.milliseconds }
        val session = EchoSession(connectedAt = clock())
        val judged = ArrayList<Judged>()
        val lines = ArrayList<String>()
        EchoLoop(
            stream = ScriptedEchoStream(clock, echoAfter),
            session = session,
            interval = interval,
            clock = clock,
            emit = { lines += "t=${clock().inWholeMilliseconds}ms $it" },
            closedBy = { EchoFailure.Exchange },
        ) { event ->
            if (event is EchoLoopEvent.Read) {
                event.read.outcomes.forEach { judged += Judged(it.seq, it.roundTrip, event.read.owedBytes, session.readDeadline) }
            }
        }.run(until = interval * exchanges)
        return Run(judged, lines)
    }

    /**
     * The walk logged rtt ≈ 256 ms with `pending` = one payload on 89% (iPhone) and 96% (Samsung) of
     * its echoes, while the qlog put the true round trip at 44 ms: the echo sat in the receive buffer
     * for the rest of the interval because no read was outstanding while the loop slept. One reply
     * that misses its read deadline, and arrives before the next send, is all it takes.
     */
    @Test
    fun oneReplyThatMissesItsDeadlineLeavesEveryLaterEchoTimedAtItsTrueRoundTrip() =
        runTest {
            val run = walk(echoAfter = { write -> if (write == 3) 200.milliseconds else trueRoundTrip }, exchanges = 24)

            val later = run.judged.filter { it.seq >= 4 }
            val skewed = later.filter { it.roundTrip != trueRoundTrip || it.owedBytes != 0 }
            val deadlineAt20 = run.judged.firstOrNull { it.seq == 20 }?.readDeadline
            assertTrue(
                later.size >= 17 && skewed.isEmpty() && deadlineAt20 != null && deadlineAt20 < 150.milliseconds,
                "seq 3's echo took 200 ms (past its ~125 ms read deadline, inside the 250 ms interval) and every other " +
                    "echo took ${trueRoundTrip.inWholeMilliseconds} ms, yet ${skewed.size} of ${later.size} echoes from seq 4 on " +
                    "were judged at a different round trip or with bytes still owed, and the read deadline at seq 20 is " +
                    "${deadlineAt20?.inWholeMilliseconds}ms (a 40 ms path's is under 150 ms). The loop is reading one echo " +
                    "behind: each reply waits in the stream until the next send's read.\n" +
                    "  judged: ${run.judged.take(8).joinToString("; ")}\n" +
                    "  seq 20: ${run.judged.firstOrNull { it.seq == 20 }}\n" +
                    "  log:\n    " + run.lines.take(12).joinToString("\n    "),
            )
        }

    /** OVERDUE is the moment the deadline passes with the reply still owed, so it precedes that reply's LATE. */
    @Test
    fun anOverdueEchoIsReportedWhenItCrossesItsDeadlineNotAtTheNextSend() =
        runTest {
            val run = walk(echoAfter = { write -> if (write == 3) 200.milliseconds else trueRoundTrip }, exchanges = 6)

            val seq3 = run.lines.filter { " seq=3 " in it }
            assertEquals(
                listOf(
                    "t=626ms ECHO-OVERDUE seq=3 waited=126ms deadline=125ms",
                    "t=700ms ECHO-LATE seq=3 rtt=200ms late=+75ms deadline=125ms pending=0B",
                ),
                seq3,
                "seq 3 was sent at 500 ms with a 125 ms deadline and echoed at 700 ms; the whole log:\n  " + run.lines.joinToString("\n  "),
            )
        }

    /** A path slower than the cadence: every echo arrives after the next send, and each keeps its own round trip. */
    @Test
    fun echoesSlowerThanTheIntervalKeepTheCadenceAndTheirOwnRoundTrip() =
        runTest {
            val run = walk(echoAfter = { 300.milliseconds }, exchanges = 20)

            val wrong = run.judged.filter { it.roundTrip != 300.milliseconds }
            assertTrue(
                run.judged.size >= 19 && wrong.isEmpty(),
                "every echo took 300 ms on a 250 ms cadence over 20 intervals: ${run.judged.size} were judged (one send " +
                    "per interval gives 19 answered in time), ${wrong.size} of them at another round trip: " +
                    run.judged.take(8).joinToString("; "),
            )
        }
}
