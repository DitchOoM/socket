package com.ditchoom.socket.testkit.walk

import com.ditchoom.socket.testkit.echo.EchoFailure
import com.ditchoom.socket.testkit.echo.EchoLoop
import com.ditchoom.socket.testkit.echo.EchoLoopEvent
import com.ditchoom.socket.testkit.echo.EchoSession
import com.ditchoom.socket.testkit.echo.EchoStream
import com.ditchoom.socket.testkit.echo.EchoWrite
import com.ditchoom.socket.testkit.echo.ScriptedEchoStream
import com.ditchoom.socket.testkit.echo.StreamReply
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Two lanes of one walk, each an [EchoLoop] with its own [LaneWatch], running concurrently the way the
 * probes run them, on virtual time. The v6 lane's stream stops answering reads after ten of them —
 * the shape of a driver loop that parks — while the v4 lane keeps echoing.
 */
class LaneIsolationTests {
    private val interval = 250.milliseconds
    private val lanes = WalkTargets(WalkTarget("178.156.248.95", 44433), listOf(WalkTarget("2a01:4ff:f4:eb1a::1", 44433))).lanes

    @Test
    fun oneLaneStallingTripsOnlyItsOwnWatchdogAndEveryLineCarriesItsLane() =
        runTest {
            val clock = { testScheduler.currentTime.milliseconds }
            val lines = ArrayList<String>()
            val bounds = StallBounds.forConnect(echoSilence = 120.seconds, handshakeBound = 30.seconds, margin = 10.seconds)
            val watches = lanes.map { LaneWatch(it, bounds, clock, startsAfter = it.stagger(interval, lanes.size)) }
            watches.forEach { watch ->
                val lane = watch.lane
                val echoes = ScriptedEchoStream(clock) { 40.milliseconds }
                val stream = if (lane.label == "v6") HangsAfterReads(10, echoes) else echoes
                backgroundScope.launch {
                    delay(lane.stagger(interval, lanes.size))
                    watch.nextAttempt()
                    watch.connected()
                    val session = EchoSession(connectedAt = clock())
                    EchoLoop(
                        stream = stream,
                        session = session,
                        interval = interval,
                        clock = clock,
                        emit = lane.log { lines += "t=${clock().inWholeMilliseconds}ms $it" },
                        closedBy = { EchoFailure.Exchange },
                    ) { event -> if (event is EchoLoopEvent.Progress) watch.progressed(event.exchanges) }
                        .run(until = 1.hours)
                }
            }

            val beats = watches.associate { it.lane.label to ArrayList<LaneWatch.Beat>() }
            repeat(4) {
                delay(60.seconds)
                watches.forEach { beats.getValue(it.lane.label) += it.beat() }
            }

            val v4 = beats.getValue("v4")
            val v6 = beats.getValue("v6")
            assertTrue(
                v4.all { it == LaneWatch.Beat.OnTime } && v6.last() is LaneWatch.Beat.Stalled,
                "the v6 lane stopped after 10 reads (loopTicks=${watches[1].loopTicks}) while v4 kept going " +
                    "(loopTicks=${watches[0].loopTicks}); each lane's watchdog must see only its own lane. beats: v4=$v4 v6=$v6",
            )

            val unscoped = lines.filterNot { it.contains(" lane=v4 ") || it.contains(" lane=v6 ") }
            assertEquals(emptyList(), unscoped, "every line a lane logs starts with its lane token")
            assertEquals(
                listOf("t=40ms lane=v4 ECHO-OK seq=1 rtt=40ms pending=0B", "t=165ms lane=v6 ECHO-OK seq=1 rtt=40ms pending=0B"),
                lines.filter { "ECHO-OK seq=1 " in it },
                "the v6 lane starts half an interval after v4",
            )
        }
}

/** [inner], except that every read after the first [reads] never returns. */
private class HangsAfterReads(
    private val reads: Int,
    private val inner: EchoStream,
) : EchoStream {
    private var done = 0

    override suspend fun write(
        payload: String,
        deadline: Duration,
    ): EchoWrite = inner.write(payload, deadline)

    override suspend fun read(deadline: Duration): StreamReply {
        done++
        if (done > reads) awaitCancellation()
        return inner.read(deadline)
    }
}
