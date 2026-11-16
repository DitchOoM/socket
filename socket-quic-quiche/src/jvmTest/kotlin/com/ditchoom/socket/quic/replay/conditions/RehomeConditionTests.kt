package com.ditchoom.socket.quic.replay.conditions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A network that comes back after an outage, on virtual time: the client's socket is reopened on the new network
 * with the same address ([com.ditchoom.socket.quic.core.edge.CoreNetworkFeedTests] does it on real sockets and the
 * wall clock, where only a slow host shows what this replays every time).
 */
class RehomeConditionTests {
    /**
     * A slow host's loopback (10 ms round trip) and a 2,000-byte echo in flight through a 3 s outage. quiche's PTO
     * probes into the outage fill the congestion window, so a PING asked for when the network came back did not
     * leave: the next backed-off PTO, 1.6 s later, resent the echo. The network's return sends the probes at once.
     */
    @Test
    fun anOutageThatFilledTheCongestionWindowStillEchoesWithinOneSecondOfTheNetworksReturn() {
        val replayed = replayTwice(flap(oneWay = 5.milliseconds, echoBytes = 2_000), Expect.Survives(firstEchoWithin = 1.seconds))
        assertEquals(1, replayed.reopens, replayed.describe())
    }

    /** The data lost in the outage is resent when the network returns, not once a PING's acknowledgement shows it lost. */
    @Test
    fun theEchoLostInTheOutageIsAnsweredOneRoundTripAfterTheNetworksReturn() {
        for (oneWay in listOf(1.milliseconds, 5.milliseconds, 25.milliseconds)) {
            for (bytes in listOf(PROBE_ECHO_BYTES, 2_000)) {
                replayTwice(flap(oneWay, bytes), Expect.Survives(firstEchoWithin = oneWay * 2))
            }
        }
    }

    private fun flap(
        oneWay: Duration,
        echoBytes: Int,
    ): Conditions {
        val delay = DelayRange(oneWay, oneWay)
        return Conditions(
            name = "Rehome after a 3 s outage, $oneWay each way, $echoBytes-byte echoes",
            source = "synthetic",
            family = Family.V4,
            evidence = Evidence.BothEnds,
            start = NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated),
            echoEvery = 200.milliseconds,
            idleTimeout = 30.seconds,
            link =
                listOf(
                    LinkWindow.Flowing(Duration.ZERO, OUTAGE_FROM, delay, PerMille(0), PerMille(0)),
                    LinkWindow.Outage(OUTAGE_FROM, BACK),
                    LinkWindow.Flowing(BACK, RUN_FOR, delay, PerMille(0), PerMille(0)),
                ),
            os =
                listOf(
                    OsEvent.Offline(OUTAGE_FROM),
                    OsEvent.Up(BACK, NetUp(NetHandle(NetKind.Wifi, 2), Validation.Validated)),
                ),
            runFor = RUN_FOR,
            echoBytes = echoBytes,
        )
    }

    private companion object {
        val OUTAGE_FROM = 3.seconds
        val BACK = 6.seconds
        val RUN_FOR = 9.seconds
    }
}
