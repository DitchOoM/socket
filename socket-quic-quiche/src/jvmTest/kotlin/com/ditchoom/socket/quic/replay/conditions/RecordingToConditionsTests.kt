package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.testkit.osnet.CellularStatus
import com.ditchoom.socket.testkit.osnet.OsLink
import com.ditchoom.socket.testkit.osnet.OsNetworkFacts
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TracePath
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What [RecordingToConditions] derives from recordings whose network is known. */
class RecordingToConditionsTests {
    private val path = TracePath(family = 4, port = 443, hi = 0L, lo = 0xC0000201L)
    private val wifi = NetworkId.Link(NetworkKind.Wifi, 501L)
    private val wifiAgain = NetworkId.Link(NetworkKind.Wifi, 502L)

    private fun up(id: NetworkId) = NetworkState.Routable(id, InternetAccess.Observed.Confirmed)

    private fun osNet(
        at: Duration,
        state: NetworkState,
        vararg addresses: String,
    ) = TraceEvent.OsNet(at, OsNetworkFacts(state, CellularStatus.NotReported, listOf(OsLink("wlan0", addresses.toList()))))

    private fun sent(
        at: Duration,
        n: Int,
    ) = TraceEvent.DgramOut(at, 40, path, "aa%04x".format(n))

    private fun received(
        at: Duration,
        n: Int,
    ) = TraceEvent.DgramIn(at, 40, path, "bb%04x".format(n))

    /**
     * An echo every 250 ms answered after 40 ms; Wi-Fi gone at 10 s while the echoes go on unanswered, and back at
     * 15 s as a new network with the same address, after which the old socket hears nothing.
     */
    @Test
    fun aFlapIsAnOutageUntilTheNetworkIsBackAndTheRouteStaysBound() {
        val events = ArrayList<TraceEvent>()
        events += TraceEvent.Net(0.milliseconds, up(wifi))
        events += osNet(1.milliseconds, up(wifi), "fe80::1", "192.0.2.10")
        for (n in 0 until 120) {
            val at = (n * 250).milliseconds
            events += sent(at, n)
            if (at < 10.seconds) events += received(at + 40.milliseconds, n)
        }
        events += TraceEvent.Net(10.seconds, NetworkState.Offline)
        events += osNet(10.3.seconds, NetworkState.Offline)
        events += TraceEvent.Net(15.seconds, up(wifiAgain))
        events += osNet(15.4.seconds, up(wifiAgain), "fe80::1", "192.0.2.10")

        val converted = RecordingToConditions.convert("flap", "test", events, window = Window.Between(0.seconds, 30.seconds))
        val conditions = assertIs<Converted.Script>(converted).conditions

        assertEquals(Family.V4, conditions.family)
        val outage = conditions.link.filterIsInstance<LinkWindow.Outage>().single()
        assertEquals(10.seconds, outage.from, "the outage starts when the OS went offline")
        assertEquals(15.seconds, outage.until, "and ends when the network is back: what stays silent then is the old socket")
        val flowing = conditions.link.filterIsInstance<LinkWindow.Flowing>()
        assertTrue(flowing.all { it.lossToPeer == PerMille(0) && it.lossFromPeer == PerMille(0) }, "no loss outside the outage: $flowing")
        assertEquals(20.milliseconds, flowing.first().delay.max, "half the echo's round trip")
        assertEquals(
            listOf(
                OsEvent.Offline(10.seconds),
                OsEvent.Route(10.seconds, RouteFrom.NoRoute),
                OsEvent.Up(15.seconds, NetUp(NetHandle(NetKind.Wifi, 2), Validation.Validated)),
                OsEvent.Route(15.seconds, RouteFrom.Bound),
            ),
            conditions.os,
            "a new network with the same address, read with the links the probe wrote for it",
        )
        assertEquals(Usable.At(15.seconds), conditions.usableAgain())
    }

    /** With the peer's recording, what each end never received is each direction's loss. */
    @Test
    fun bothRecordingsGiveTheLossOfEachDirection() {
        val ours = ArrayList<TraceEvent>()
        val peer = ArrayList<TraceEvent>()
        ours += TraceEvent.Net(0.milliseconds, up(wifi))
        for (n in 0 until 100) {
            val at = (n * 100).milliseconds
            ours += sent(at, n)
            if (n % 10 != 0) peer += TraceEvent.DgramIn(at + 20.milliseconds, 40, path, "aa%04x".format(n))
            peer += TraceEvent.DgramOut(at + 25.milliseconds, 40, path, "bb%04x".format(n))
            if (n % 5 != 0) ours += received(at + 45.milliseconds, n)
        }

        val converted = RecordingToConditions.convert("both", "test", ours, peer, window = Window.Between(0.seconds, 10.seconds))
        val conditions = assertIs<Converted.Script>(converted).conditions

        assertEquals(Evidence.BothEnds, conditions.evidence)
        val windows = conditions.link.filterIsInstance<LinkWindow.Flowing>()
        assertEquals(1, windows.size, "one window: ${conditions.link}")
        assertEquals(PerMille(100), windows.single().lossToPeer, "every tenth of ours never reached the peer")
        assertEquals(PerMille(200), windows.single().lossFromPeer, "every fifth of the peer's never reached us")
    }
}
