package com.ditchoom.socket.testkit.echo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The adaptive echo rate: 250 ms while anything is happening, 5 s while the device is still and the
 * connection steady. A fixed 250 ms kept the radio awake for the whole walk (the Samsung drained
 * ~4 %/h, a day per charge); these pin which signals hold the fast rate and for how long.
 */
class EchoPaceTests {
    private val device = DeviceSignals()
    private val pace = EchoPace.Adaptive(device)

    private fun at(t: Duration) = pace.next(t)

    @Test
    fun aNewConnectionEchoesFastThenSettlesToIdle() {
        pace.connected(0.seconds)
        assertEquals(PaceStep.Changed(250.milliseconds, "PACE fast reason=settling intervalMs=250"), at(1.seconds))
        assertEquals(PaceStep.Same(250.milliseconds), at(9.seconds))
        assertEquals(PaceStep.Changed(5.seconds, "PACE idle intervalMs=5000"), at(10.seconds))
        assertEquals(PaceStep.Same(5.seconds), at(5.minutes))
    }

    @Test
    fun troubleSnapsToFastAndHoldsThirtySecondsPastTheLastOfIt() {
        pace.connected(0.seconds)
        at(20.seconds)
        pace.troubled(60.seconds)
        assertEquals(PaceStep.Changed(250.milliseconds, "PACE fast reason=trouble intervalMs=250"), at(61.seconds))
        pace.troubled(80.seconds)
        assertEquals(250.milliseconds, at(109.seconds).interval)
        assertEquals(5.seconds, at(110.seconds).interval)
    }

    @Test
    fun aDeviceNetworkChangeOrALanePathChangeHoldsFast() {
        pace.connected(0.seconds)
        at(20.seconds)
        device.networkChanged(100.seconds)
        assertEquals(PaceStep.Changed(250.milliseconds, "PACE fast reason=network intervalMs=250"), at(101.seconds))
        assertEquals(5.seconds, at(130.seconds).interval)
        pace.pathChanged(200.seconds)
        assertEquals(250.milliseconds, at(229.seconds).interval)
    }

    @Test
    fun movementHoldsFastForTwoMinutesAfterTheLastSignOfIt() {
        pace.connected(0.seconds)
        at(20.seconds)
        device.moved(30.seconds)
        assertEquals(PaceStep.Changed(250.milliseconds, "PACE fast reason=moving intervalMs=250"), at(31.seconds))
        assertEquals(250.milliseconds, at(149.seconds).interval)
        assertEquals(5.seconds, at(150.seconds).interval)
    }

    @Test
    fun theStrongestReasonIsTheOneNamed() {
        pace.connected(0.seconds)
        device.moved(0.seconds)
        pace.troubled(1.seconds)
        assertEquals(PaceStep.Changed(250.milliseconds, "PACE fast reason=trouble intervalMs=250"), at(2.seconds))
    }

    /**
     * The read deadline tracks the round trip closely, so ordinary jitter crosses it by milliseconds; on
     * the iPhone that happened about once a minute and, counted as trouble, held a still phone fast.
     */
    @Test
    fun anEchoAFewMillisecondsLateIsJitterNotTrouble() =
        runTest {
            val clock = { testScheduler.currentTime.milliseconds }
            val lines = ArrayList<String>()
            val stream =
                ScriptedEchoStream(clock) { _ ->
                    // About one echo in ten crosses the deadline by tens of milliseconds, as on Wi-Fi.
                    if ((clock().inWholeMilliseconds / 1000) % 7 == 3L) 180.milliseconds else 40.milliseconds
                }
            EchoLoop(
                stream = stream,
                session = EchoSession(connectedAt = clock()),
                pace = pace,
                clock = clock,
                emit = { lines += it },
                closedBy = { EchoFailure.Exchange },
            ) { }.run(until = 2.minutes)
            assertTrue(lines.any { it.startsWith("ECHO-LATE") }, "the script must make some echoes late: ${lines.take(40)}")
            assertTrue(lines.none { it == "PACE fast reason=trouble intervalMs=250" }, lines.filter { "PACE" in it }.toString())
        }

    /** End to end on virtual time: a steady stream goes idle after settling, and a late echo brings it back. */
    @Test
    fun theLoopSlowsDownOnceSteadyAndSpeedsUpWhenAnEchoIsLate() =
        runTest {
            val clock = { testScheduler.currentTime.milliseconds }
            val lines = ArrayList<String>()
            var writes = 0
            val stream =
                ScriptedEchoStream(clock) { write ->
                    writes = write
                    if (clock() in 60.seconds..61.seconds) 2.seconds else 40.milliseconds
                }
            EchoLoop(
                stream = stream,
                session = EchoSession(connectedAt = clock()),
                pace = pace,
                clock = clock,
                emit = { lines += "t=${clock().inWholeMilliseconds}ms $it" },
                closedBy = { EchoFailure.Exchange },
            ) { }.run(until = 2.minutes)
            val paceLines = lines.filter { "PACE" in it || "LOOP-SCHEDULE" in it }
            assertEquals(
                "LOOP-SCHEDULE read=held-until-answered pace=adaptive intervalMs=250 idleMs=5000",
                paceLines[0].substringAfter("ms "),
            )
            assertTrue(paceLines[1].endsWith("PACE fast reason=settling intervalMs=250"), "$paceLines")
            assertTrue(paceLines[2].endsWith("PACE idle intervalMs=5000"), "$paceLines")
            assertTrue(paceLines.drop(3).any { it.endsWith("PACE fast reason=trouble intervalMs=250") }, "$paceLines")
            // ~40 fast rounds settling, then idle at 5 s with one fast burst: far fewer than 480 at a fixed 250 ms.
            assertTrue(writes in 60..250, "wrote $writes echoes in two minutes: $paceLines")
        }
}
