@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.testkit

import com.ditchoom.socket.BlockReason
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.MonitorCapability
import com.ditchoom.socket.MonitorMechanism
import com.ditchoom.socket.NetworkMonitorScript
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.ReachResolution
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class NetworkMonitorRecorderTests {
    private val wifi: NetworkId = NetworkId.Link(NetworkKind.Wifi, handle = 1)
    private val cellular: NetworkId = NetworkId.Link(NetworkKind.Cellular, handle = 2)

    private val fullLadder = MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteAndInternet)

    private fun confirmed(id: NetworkId) = NetworkState.Routable(id, InternetAccess.Observed.Confirmed)

    /** A [TraceSink] that captures every emitted event in order. */
    private class CapturingSink : TraceSink {
        val events = mutableListOf<TraceEvent>()

        override fun emit(event: TraceEvent) {
            events += event
        }
    }

    private val flapScript =
        networkMonitorScript(fullLadder, initialState = confirmed(wifi)) {
            after(1.seconds) { state(confirmed(cellular)) }
            after(500.milliseconds) { state(NetworkState.Offline) }
        }

    /** Record a scripted monitor's playback into [sink]; returns after the script is exhausted. */
    private suspend fun TestScope.record(
        script: NetworkMonitorScript,
        sink: CapturingSink,
    ) {
        val monitor = ScriptedNetworkMonitor(script)
        val recorder = NetworkMonitorRecorder(sink) { testScheduler.currentTime.milliseconds }
        recorder.observe(monitor, backgroundScope)
        runCurrent() // record the capability + the flow's initial replayed value at t=0
        monitor.play()
        runCurrent() // flush the final transition's emission into the recorder
    }

    @Test
    fun recordsCapabilityThenStatesWithVirtualTimestamps() =
        runTest {
            val sink = CapturingSink()
            record(flapScript, sink)
            assertEquals(
                listOf<TraceEvent>(
                    TraceEvent.NetCapability(Duration.ZERO, fullLadder),
                    TraceEvent.Net(Duration.ZERO, confirmed(wifi)),
                    TraceEvent.Net(1.seconds, confirmed(cellular)),
                    TraceEvent.Net(1500.milliseconds, NetworkState.Offline),
                ),
                sink.events.toList(),
            )
        }

    /**
     * The defect this collapse fixed: the old recorder launched one collector per flow, so two
     * independently-stamped streams interleaved by scheduling and the 2026-07-29 device capture emitted
     * an earlier availability line *after* a later identity line. One flow means one collector, so
     * monotonicity is structural — assert it rather than trusting it.
     */
    @Test
    fun theRecordedStreamIsMonotonicInTime() =
        runTest {
            val sink = CapturingSink()
            record(flapScript, sink)
            val stamps = sink.events.map { it.at }
            assertEquals(stamps.sorted(), stamps, "a single-collector recording can never go back in time")
        }

    @Test
    fun fromTraceReconstructsCapabilityInitialStateAndTransitions() =
        runTest {
            val sink = CapturingSink()
            record(flapScript, sink)
            val rebuilt = networkMonitorScriptFromTrace(sink.events)
            assertEquals(fullLadder, rebuilt.capability)
            assertEquals(confirmed(wifi), rebuilt.initialState)
            assertEquals(
                listOf(
                    NetworkMonitorScript.Transition(1.seconds, confirmed(cellular)),
                    NetworkMonitorScript.Transition(1500.milliseconds, NetworkState.Offline),
                ),
                rebuilt.transitions,
            )
        }

    @Test
    fun recordReplayRecordIsAFixpoint() =
        runTest {
            // Record the original, rebuild a script from the trace, replay THAT, record again.
            // The two traces must be identical — the record→replay loop is lossless for network events,
            // capability included.
            val first = CapturingSink()
            record(flapScript, first)

            val replayed = networkMonitorScriptFromTrace(first.events)
            val second = CapturingSink()
            record(replayed, second)

            assertEquals(first.events.toList(), second.events.toList())
        }

    @Test
    fun emptyTraceYieldsUnknown() {
        val script = networkMonitorScriptFromTrace(emptyList())
        assertEquals(NetworkState.Unknown, script.initialState)
        assertEquals(emptyList(), script.transitions)
    }

    @Test
    fun fromTraceIgnoresNonNetworkEvents() {
        // A mixed trace (e.g. a QUIC recording) projects to just its network observations.
        val mixed =
            listOf(
                TraceEvent.NetCapability(Duration.ZERO, fullLadder),
                TraceEvent.DgramOut(5_000.nanoseconds, len = 3, path = null, payloadHex = "abcdef"),
                TraceEvent.Net(Duration.ZERO, confirmed(wifi)),
                TraceEvent.State(6_000.nanoseconds, name = "Established", detail = null),
                TraceEvent.Net(2.seconds, confirmed(cellular)),
            )
        val script = networkMonitorScriptFromTrace(mixed)
        assertEquals(confirmed(wifi), script.initialState)
        assertEquals(
            listOf(NetworkMonitorScript.Transition(2.seconds, confirmed(cellular))),
            script.transitions,
        )
    }

    /**
     * A trace with no `NET_CAP` line — a QUIC-only capture, or one recorded before that line existed —
     * still replays through a *validating* scripted monitor, by deriving the weakest capability that
     * could have produced the whole timeline rather than trusting the fixture author.
     */
    @Test
    fun aTraceWithoutCapabilityDerivesTheWeakestOneThatFits() {
        val observed =
            listOf(
                TraceEvent.Net(Duration.ZERO, confirmed(wifi)),
                TraceEvent.Net(1.seconds, NetworkState.Routable(wifi, InternetAccess.Observed.Blocked(BlockReason.Suspended))),
            )
        // A verdict was recorded, so only a full-ladder monitor explains it.
        assertEquals(
            MonitorCapability(MonitorMechanism.Unknown, ReachResolution.RouteAndInternet),
            networkMonitorScriptFromTrace(observed).capability,
        )

        // LinkLocal needs route visibility but no verdict — RouteOnly is the weakest that fits.
        val routes =
            listOf(
                TraceEvent.Net(Duration.ZERO, NetworkState.LinkLocal(wifi)),
                TraceEvent.Net(1.seconds, NetworkState.Routable(wifi, InternetAccess.Unobserved)),
            )
        assertEquals(ReachResolution.RouteOnly, networkMonitorScriptFromTrace(routes).capability.resolution)

        // Offline + Routable(Unobserved) only — a browser/Node timeline.
        val linkOnly =
            listOf(
                TraceEvent.Net(Duration.ZERO, NetworkState.Offline),
                TraceEvent.Net(1.seconds, NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Unobserved)),
            )
        assertEquals(ReachResolution.LinkOnly, networkMonitorScriptFromTrace(linkOnly).capability.resolution)
    }

    @Test
    fun weakestCapabilityFallsBackToAssertedForATimelineNoResolutionExplains() {
        // Hand-edited: mixes an observed verdict with unobserved reachability. No real monitor does both.
        val impossible =
            listOf(
                confirmed(wifi),
                NetworkState.Routable(wifi, InternetAccess.Unobserved),
            )
        assertEquals(ReachResolution.Asserted, weakestCapabilityFor(impossible).resolution)
    }
}
