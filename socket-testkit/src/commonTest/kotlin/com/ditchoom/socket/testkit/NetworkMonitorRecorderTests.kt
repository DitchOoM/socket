@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.testkit

import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.NetworkMonitorScript
import com.ditchoom.socket.ScriptedNetworkMonitor
import com.ditchoom.socket.networkMonitorScript
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NetworkMonitorRecorderTests {
    private val wifi: NetworkId = NetworkId.Link(NetworkKind.Wifi, handle = 1)
    private val cellular: NetworkId = NetworkId.Link(NetworkKind.Cellular, handle = 2)

    /** A [TraceSink] that captures every emitted event in order. */
    private class CapturingSink : TraceSink {
        val events = mutableListOf<TraceEvent>()

        override fun emit(event: TraceEvent) {
            events += event
        }
    }

    private val flapScript =
        networkMonitorScript(
            initialAvailability = NetworkAvailability.AVAILABLE,
            initialNetworkId = wifi,
        ) {
            after(1.seconds) { networkId(cellular) }
            after(500.milliseconds) { availability(NetworkAvailability.UNAVAILABLE) }
        }

    /** Record a scripted monitor's playback into [sink]; returns after the script is exhausted. */
    private suspend fun TestScope.record(
        script: NetworkMonitorScript,
        sink: CapturingSink,
    ) {
        val monitor = ScriptedNetworkMonitor(script)
        val recorder = NetworkMonitorRecorder(sink) { testScheduler.currentTime.milliseconds }
        recorder.observe(monitor, backgroundScope)
        runCurrent() // record each flow's initial replayed value at t=0
        monitor.play()
        runCurrent() // flush the final transition's emission into the recorder
    }

    @Test
    fun recordsInitialValuesAndTransitionsWithVirtualTimestamps() =
        runTest {
            val sink = CapturingSink()
            record(flapScript, sink)
            assertEquals(
                listOf<TraceEvent>(
                    TraceEvent.NetAvail(0, NetworkAvailability.AVAILABLE),
                    TraceEvent.Net(0, wifi),
                    TraceEvent.Net(1_000_000_000, cellular),
                    TraceEvent.NetAvail(1_500_000_000, NetworkAvailability.UNAVAILABLE),
                ),
                sink.events.toList(),
            )
        }

    @Test
    fun fromTraceReconstructsInitialStateAndTransitions() =
        runTest {
            val sink = CapturingSink()
            record(flapScript, sink)
            val rebuilt = networkMonitorScriptFromTrace(sink.events)
            assertEquals(NetworkAvailability.AVAILABLE, rebuilt.initialAvailability)
            assertEquals(wifi, rebuilt.initialNetworkId)
            assertEquals(
                listOf<NetworkMonitorScript.Transition>(
                    NetworkMonitorScript.Transition.Network(1.seconds, cellular),
                    NetworkMonitorScript.Transition.Availability(1500.milliseconds, NetworkAvailability.UNAVAILABLE),
                ),
                rebuilt.transitions,
            )
        }

    @Test
    fun recordReplayRecordIsAFixpoint() =
        runTest {
            // Record the original, rebuild a script from the trace, replay THAT, record again.
            // The two traces must be identical — the record→replay loop is lossless for network events.
            val first = CapturingSink()
            record(flapScript, first)

            val replayed = networkMonitorScriptFromTrace(first.events)
            val second = CapturingSink()
            record(replayed, second)

            assertEquals(first.events.toList(), second.events.toList())
        }

    @Test
    fun emptyTraceYieldsSteadyDefaults() {
        val script = networkMonitorScriptFromTrace(emptyList())
        assertEquals(NetworkAvailability.AVAILABLE, script.initialAvailability)
        assertEquals(NetworkId.Unidentified, script.initialNetworkId)
        assertEquals(emptyList(), script.transitions)
    }

    @Test
    fun fromTraceIgnoresNonNetworkEvents() {
        // A mixed trace (e.g. a QUIC recording) projects to just its network observations.
        val mixed =
            listOf(
                TraceEvent.NetAvail(0, NetworkAvailability.AVAILABLE),
                TraceEvent.DgramOut(5_000, len = 3, path = null, payloadHex = "abcdef"),
                TraceEvent.Net(0, wifi),
                TraceEvent.State(6_000, name = "Established", detail = null),
                TraceEvent.Net(2_000_000_000, cellular),
            )
        val script = networkMonitorScriptFromTrace(mixed)
        assertEquals(wifi, script.initialNetworkId)
        assertEquals(
            listOf<NetworkMonitorScript.Transition>(
                NetworkMonitorScript.Transition.Network(2.seconds, cellular),
            ),
            script.transitions,
        )
    }
}
