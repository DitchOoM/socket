package com.ditchoom.socket.quic.replay.conditions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A NAT rebinds the client to a new port in the middle of a download, right after the client acknowledged what had
 * arrived: the rest of the server's flight, and every retransmission of it, goes to the old port and is lost. The
 * client has a read waiting and nothing of its own in flight, so only the awaiting-peer probe makes it speak: its PING
 * reaches the server from the new port, the server validates that path (RFC 9000 §9.3) and the download finishes. On
 * virtual time over a real round trip.
 */
class RebindConditionTests {
    /** The client's PING leaves within the floor of the last arrival, and the download finishes a round trip or so later. */
    @Test
    fun aDownloadWhoseFlightARebindingLostFinishesOnTheClientsProbe() {
        val replayed = ConditionReplay(clean(), workload = Workload.Download(DOWNLOAD), rebinds = listOf(REBIND_AT)).run()
        val done = replayed.downloaded.firstOrNull() ?: throw AssertionError("the download never finished: ${replayed.describe()}")
        val pinged = replayed.pathMarks.filter { it.phase == "Pinged" }.map { it.at }
        println("[rebind] download done at $done, rebound at $REBIND_AT; probes at $pinged (flight clock)")
        assertTrue(done > REBIND_AT, "the download finished before the rebinding at $REBIND_AT, so nothing was lost: at $done")
        assertTrue(
            done <= REBIND_AT + FLOOR + SETTLE,
            "the download finished at $done, past ${REBIND_AT + FLOOR + SETTLE}: the client stayed silent. ${replayed.describe()}",
        )
        assertTrue("Pinged" in replayed.pathMarks.map { it.phase }, "the client never probed: ${replayed.pathMarks}")
        assertTrue(replayed.outcome is Outcome.Survived, replayed.describe())
    }

    /** The same download with no rebinding sends no probe: the client hears from the server throughout. */
    @Test
    fun aDownloadThatIsAnsweredThroughoutSendsNoProbe() {
        val replayed = ConditionReplay(clean(), workload = Workload.Download(DOWNLOAD)).run()
        assertEquals(1, replayed.downloaded.size, replayed.describe())
        assertEquals(emptyList(), replayed.pathMarks.filter { it.phase == "Pinged" }, "a probe on a path that answered")
    }

    /** An idle connection, no read waiting, is never probed, however long it is quiet. */
    @Test
    fun anIdleConnectionIsNeverProbed() {
        val replayed = ConditionReplay(clean().copy(echoEvery = RUN_FOR * 2)).run()
        assertTrue(replayed.outcome is Outcome.Survived, replayed.describe())
        assertEquals(emptyList(), replayed.pathMarks.filter { it.phase == "Pinged" }, "an idle connection was probed")
    }

    private fun clean(): Conditions =
        Conditions(
            name = "A clean 30 ms link, rebound by a NAT",
            source = "synthetic",
            family = Family.V4,
            evidence = Evidence.BothEnds,
            start = NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated),
            echoEvery = 200.milliseconds,
            idleTimeout = IDLE,
            link = listOf(LinkWindow.Flowing(Duration.ZERO, RUN_FOR, DELAY, PerMille(0), PerMille(0))),
            os = emptyList(),
            runFor = RUN_FOR,
        )

    private companion object {
        val DELAY = DelayRange(30.milliseconds, 30.milliseconds)

        /** Mid-download: the handshake ends near 0.12 s and the request leaves a second later. */
        val REBIND_AT = 1_300.milliseconds
        const val DOWNLOAD = 400_000

        /** The awaiting-peer probe's floor: how long the client waits with nothing arriving. */
        val FLOOR = 2.seconds

        /** The server validating the new path and resending the rest of the download. */
        val SETTLE = 1.seconds
        val IDLE = 30.seconds
        val RUN_FOR = 10.seconds
    }
}
