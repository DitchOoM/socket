package com.ditchoom.socket.testkit.osnet

import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.MonitorCapability
import com.ditchoom.socket.MonitorMechanism
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.ReachResolution
import com.ditchoom.socket.ScriptedNetworkMonitor
import com.ditchoom.socket.networkMonitorScript
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [OsNetWatch] against a fake source and a scripted monitor: what a device reports, on any platform,
 * with no device.
 */
class OsNetWatchTests {
    /** A source whose reading the test moves, and which declares how it would learn of a change. */
    private class FakeSource(
        var reading: OsNetworkReading,
        override val cellularSignal: CellularSignal = CellularSignal.Signalled,
    ) : OsNetworkSource {
        var reads = 0

        override fun read(): OsNetworkReading {
            reads++
            return reading
        }
    }

    private class Recording {
        val lines = mutableListOf<String>()
        val events = mutableListOf<TraceEvent>()
        val sink = TraceSink { events += it }
    }

    private fun readingOf(facts: OsNetworkFacts) = OsNetworkReading(facts.cellular, facts.links)

    private fun watchOn(
        source: FakeSource,
        recording: Recording,
        clock: () -> Duration = { Duration.ZERO },
    ) = OsNetWatch(source, clock, { recording.lines += it }, recording.sink)

    @Test
    fun everyChangeRendersExactlyOneLineAndExactlyOneTraceEvent() {
        val recording = Recording()
        val source = FakeSource(readingOf(OsNetworkFixtures.registeredNoBearer))
        var now = Duration.ZERO
        val watch = watchOn(source, recording) { now }

        // The three states a phone really passes through in a lift: radio registered with no bearer,
        // Wi-Fi associating, Wi-Fi up — the ladder reads Offline, Offline, Routable.
        watch.sample(OsNetworkFixtures.registeredNoBearer.state)
        now = 1.seconds
        source.reading = readingOf(OsNetworkFixtures.wifiScanning)
        watch.sample(OsNetworkFixtures.wifiScanning.state)
        now = 2.seconds
        source.reading = readingOf(OsNetworkFixtures.wifiNoGlobalV6)
        watch.sample(OsNetworkFixtures.wifiNoGlobalV6.state)

        assertEquals(
            listOf(
                "OS-NET ${OsNetworkFixtures.registeredNoBearer.line}",
                "OS-NET ${OsNetworkFixtures.wifiScanning.line}",
                "OS-NET ${OsNetworkFixtures.wifiNoGlobalV6.line}",
            ),
            recording.lines,
        )
        assertEquals(
            listOf<TraceEvent>(
                TraceEvent.OsNet(Duration.ZERO, OsNetworkFixtures.registeredNoBearer),
                TraceEvent.OsNet(1.seconds, OsNetworkFixtures.wifiScanning),
                TraceEvent.OsNet(2.seconds, OsNetworkFixtures.wifiNoGlobalV6),
            ),
            recording.events,
        )
    }

    @Test
    fun aSampleThatFindsNothingChangedWritesNothingAtAll() {
        val recording = Recording()
        val source = FakeSource(readingOf(OsNetworkFixtures.wifiNoGlobalV6))
        val watch = watchOn(source, recording)

        repeat(21) { watch.sample(OsNetworkFixtures.wifiNoGlobalV6.state) }

        assertEquals(1, recording.lines.size, recording.lines.toString())
        assertEquals(1, recording.events.size)
        // It still LOOKED every time — a quiet log means the OS was quiet, not that nobody asked.
        assertEquals(21, source.reads)
    }

    @Test
    fun aChangeInACellularFieldAloneIsARecordedChange() {
        val recording = Recording()
        val registered = OsNetworkFixtures.registeredNoBearer
        val source = FakeSource(readingOf(registered))
        val watch = watchOn(source, recording)
        watch.sample(registered.state)

        // Data roaming switched on: same rung, same links, a bearer where there was none. The ladder
        // sees nothing; this is the whole point of recording the radio.
        val connected = (registered.cellular as CellularStatus.Reported).copy(data = CellularData.Connected)
        source.reading = OsNetworkReading(connected, registered.links)

        watch.sample(registered.state)
        assertEquals(2, recording.lines.size)
        assertTrue(recording.lines[1].contains("data=Connected"), recording.lines[1])
    }

    @Test
    fun theSourceLineDeclaresWhatThisDeviceCanBeAsked() {
        val recording = Recording()
        val android = FakeSource(readingOf(OsNetworkFixtures.wifiNoGlobalV6), CellularSignal.Signalled)
        watchOn(android, recording)
            .declare(MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteAndInternet))

        val desktop = FakeSource(OsNetworkReading(CellularStatus.NotReported, emptyList()), CellularSignal.NotReported)
        watchOn(desktop, recording)
            .declare(MonitorCapability(MonitorMechanism.Polled(5.seconds), ReachResolution.RouteOnly))

        assertEquals(
            listOf(
                "OS-NET-SOURCE monitor=PlatformSignalled/RouteAndInternet cellular=Signalled",
                "OS-NET-SOURCE monitor=Polled(5000000000)/RouteOnly cellular=NotReported",
            ),
            recording.lines,
        )
    }

    @Test
    fun aConnectionsOwnTraceIsSeededWithTheNetworkTheDeviceWasAlreadyOn() {
        val recording = Recording()
        val source = FakeSource(readingOf(OsNetworkFixtures.cellularWithGlobalV6))
        var now = Duration.ZERO
        val watch = watchOn(source, recording) { now }
        val connection = Recording()

        // Before the first sample there is nothing recorded, so nothing is seeded: a fabricated
        // baseline would be worse than none.
        watch.seed(connection.sink)
        assertEquals(emptyList<TraceEvent>(), connection.events)

        watch.sample(OsNetworkFixtures.cellularWithGlobalV6.state)
        now = 30.seconds
        watch.seed(connection.sink)

        assertEquals(listOf<TraceEvent>(TraceEvent.OsNet(30.seconds, OsNetworkFixtures.cellularWithGlobalV6)), connection.events)
    }

    @Test
    fun followRecordsOneChangePerObservationIncludingTheChatterTheLadderDeDupesAway() =
        runTest {
            val recording = Recording()
            val source = FakeSource(readingOf(OsNetworkFixtures.wifiNoGlobalV6))
            val watch = watchOn(source, recording)
            val wifi = NetworkId.Link(NetworkKind.Wifi, 441492361229L)
            val script =
                networkMonitorScript(
                    capability = MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteAndInternet),
                    initialState = NetworkState.Routable(wifi, InternetAccess.Observed.Pending),
                ) {
                    after(200.milliseconds) { state(NetworkState.Routable(wifi, InternetAccess.Observed.Confirmed)) }
                    // The same rung twice — a radio flapping while the fold lands back where it was.
                    // `state` publishes nothing here; `observations` does, and so must this record,
                    // because the OS reading underneath it moved.
                    after(200.milliseconds) { state(NetworkState.Routable(wifi, InternetAccess.Observed.Confirmed)) }
                }
            val monitor = ScriptedNetworkMonitor(script)

            val follower = backgroundScope.launch { watch.follow(monitor) }
            testScheduler.runCurrent()
            source.reading = readingOf(OsNetworkFixtures.wifiScanning)
            monitor.play()
            testScheduler.runCurrent()
            follower.cancel()

            val osNet = recording.lines.filter { it.startsWith("OS-NET ") }
            assertEquals(
                listOf(
                    // initial Pending, with the Wi-Fi reading the watch was constructed on
                    "OS-NET state=Routable|Link:Wifi:441492361229|Pending v4=SiteLocal v6=LinkLocal " +
                        "cell=Reported(sim=Ready,reg=InService,data=Disconnected,roaming=Roaming,bearer=Lte) " +
                        "links=wlan0=192.168.1.6,fe80::4c2a:8bff:fe31:9f10",
                    // Confirmed, by which time the links had dropped their address
                    "OS-NET state=Routable|Link:Wifi:441492361229|Confirmed v4=Absent v6=Absent " +
                        "cell=Reported(sim=Ready,reg=InService,data=Disconnected,roaming=Roaming,bearer=Lte) " +
                        "links=wlan0=-",
                ),
                osNet,
            )
            assertEquals("OS-NET-SOURCE monitor=PlatformSignalled/RouteAndInternet cellular=Signalled", recording.lines.first())
            // The repeated Confirmed observation was sampled and found unchanged: 3 observations, 2 records.
            assertEquals(3, source.reads)
        }
}
