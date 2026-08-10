@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.testkit

import com.ditchoom.socket.BlockReason
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.MonitorCapability
import com.ditchoom.socket.MonitorMechanism
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkMonitorScript
import com.ditchoom.socket.NetworkObservation
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.ObservationSequence
import com.ditchoom.socket.ReachResolution
import com.ditchoom.socket.ScriptedNetworkMonitor
import com.ditchoom.socket.networkMonitorScript
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    /**
     * Record a scripted monitor's playback into [sink]; returns the played monitor after the script is
     * exhausted, so a test can also read the density the ride reported.
     */
    private suspend fun TestScope.record(
        script: NetworkMonitorScript,
        sink: CapturingSink,
    ): ScriptedNetworkMonitor {
        val monitor = ScriptedNetworkMonitor(script)
        val recorder = NetworkMonitorRecorder(sink) { testScheduler.currentTime.milliseconds }
        recorder.observe(monitor, backgroundScope)
        runCurrent() // record the capability + the flow's initial replayed value at t=0
        monitor.play()
        runCurrent() // flush the final transition's emission into the recorder
        return monitor
    }

    /**
     * A monitor whose observation stream is handed to it verbatim — the only way to record a *specific*
     * sequence gap without racing a real relay's `DROP_OLDEST` buffer against a collector.
     */
    private class ScriptedObservations(
        override val capability: MonitorCapability,
        private val emissions: List<NetworkObservation>,
    ) : NetworkMonitor {
        private val _state = MutableStateFlow(emissions.first().state)
        override val state: StateFlow<NetworkState> = _state.asStateFlow()

        override val observations: Flow<NetworkObservation> =
            flow {
                for (emission in emissions) {
                    _state.value = emission.state
                    emit(emission)
                }
            }

        override fun close() {}
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

    /**
     * The capture gap this recorder had, in the one case that most deserves recording: a link flapping
     * hard while every evaluation folds back to the **same** state.
     *
     * Driven by [com.ditchoom.socket.NetworkMonitor.state] this records nothing at all — a `StateFlow`
     * de-dupes an emission equal to the current value, so the entire burst collapses to the single
     * initial line and a replayed ride reports a network that was perfectly quiet. Exactly backwards:
     * the burst *is* the instability signal. Collecting
     * [observations][com.ditchoom.socket.NetworkMonitor.observations] preserves every callback.
     *
     * Note the trace is otherwise unremarkable — same event type, same stamps, no new schema. The
     * repeats simply stop being thrown away.
     */
    @Test
    fun aBurstFoldingBackToTheSameStateIsRecordedInFull() =
        runTest {
            val chatter =
                networkMonitorScript(fullLadder, initialState = confirmed(wifi)) {
                    after(100.milliseconds) { state(confirmed(wifi)) }
                    after(100.milliseconds) { state(confirmed(wifi)) }
                    after(100.milliseconds) { state(confirmed(wifi)) }
                }
            val sink = CapturingSink()
            record(chatter, sink)

            assertEquals(
                listOf<TraceEvent>(
                    TraceEvent.NetCapability(Duration.ZERO, fullLadder),
                    TraceEvent.Net(Duration.ZERO, confirmed(wifi)),
                    TraceEvent.Net(100.milliseconds, confirmed(wifi)),
                    TraceEvent.Net(200.milliseconds, confirmed(wifi)),
                    TraceEvent.Net(300.milliseconds, confirmed(wifi)),
                ),
                sink.events.toList(),
                "every observation is recorded, including the three that folded to no visible change",
            )
        }

    /**
     * Closing the loop the whole change exists for: the density a real device produced survives capture
     * *and* replay, so a threshold tuned against a recorded ride is tuned against the real callback rate
     * rather than a synthetic guess.
     */
    @Test
    fun replayingABurstReproducesTheObservationDensityItWasCapturedWith() =
        runTest {
            val chatter =
                networkMonitorScript(fullLadder, initialState = confirmed(wifi)) {
                    after(100.milliseconds) { state(confirmed(wifi)) }
                    after(100.milliseconds) { state(confirmed(wifi)) }
                    after(100.milliseconds) { state(confirmed(wifi)) }
                }
            val sink = CapturingSink()
            record(chatter, sink)

            val replayed = ScriptedNetworkMonitor(networkMonitorScriptFromTrace(sink.events))
            assertEquals(0L, replayed.observationCount.value, "nothing observed before playback starts")
            replayed.play()

            assertEquals(
                3L,
                replayed.observationCount.value,
                "the three post-initial observations replay as three, not as the zero state-changes they were",
            )
        }

    /**
     * The capture gap #315 closes: a burst deep enough to overrun the relay's 64 slots reaches the
     * recorder with observations *missing*, and the trace has to say so. A `NET_GAP` line precedes the
     * `NET` it modifies — dropped observations happened, then this state was seen — and shares its stamp,
     * because the gap is a property of that observation rather than an event with an instant of its own.
     *
     * The opening emission carries sequence 5 and reports **nothing**: a subscriber that arrived at 5
     * lost nothing, it simply was not there for 1..4. Only a gap between two emissions this collector
     * actually saw is a gap it can honestly report.
     */
    @Test
    fun aSequenceGapIsRecordedAsNetGapImmediatelyBeforeItsState() =
        runTest {
            val monitor =
                ScriptedObservations(
                    fullLadder,
                    listOf(
                        NetworkObservation.Sequenced(confirmed(wifi), ObservationSequence(5)),
                        NetworkObservation.Sequenced(confirmed(wifi), ObservationSequence(6)),
                        NetworkObservation.Sequenced(confirmed(cellular), ObservationSequence(43)),
                        NetworkObservation.Sequenced(NetworkState.Offline, ObservationSequence(44)),
                    ),
                )
            val sink = CapturingSink()
            NetworkMonitorRecorder(sink) { testScheduler.currentTime.milliseconds }.observe(monitor, backgroundScope)
            runCurrent()

            assertEquals(
                listOf<TraceEvent>(
                    TraceEvent.NetCapability(Duration.ZERO, fullLadder),
                    TraceEvent.Net(Duration.ZERO, confirmed(wifi)),
                    TraceEvent.Net(Duration.ZERO, confirmed(wifi)),
                    TraceEvent.NetGap(Duration.ZERO, dropped = 36),
                    TraceEvent.Net(Duration.ZERO, confirmed(cellular)),
                    TraceEvent.Net(Duration.ZERO, NetworkState.Offline),
                ),
                sink.events.toList(),
            )
        }

    /**
     * A monitor that does not report density makes **no** gap claim at all — not even a confirmed zero.
     * The sealed [NetworkObservation] distinction survives to the wire: an absent `NET_GAP` line means
     * "nothing known about density here", which is exactly what an [NetworkObservation.Unsequenced]
     * stream knows.
     */
    @Test
    fun anUnsequencedMonitorNeverRecordsAGap() =
        runTest {
            val monitor =
                ScriptedObservations(
                    fullLadder,
                    listOf(
                        NetworkObservation.Unsequenced(confirmed(wifi)),
                        NetworkObservation.Unsequenced(confirmed(cellular)),
                        NetworkObservation.Unsequenced(NetworkState.Offline),
                    ),
                )
            val sink = CapturingSink()
            NetworkMonitorRecorder(sink) { testScheduler.currentTime.milliseconds }.observe(monitor, backgroundScope)
            runCurrent()

            assertEquals(
                listOf<TraceEvent>(
                    TraceEvent.NetCapability(Duration.ZERO, fullLadder),
                    TraceEvent.Net(Duration.ZERO, confirmed(wifi)),
                    TraceEvent.Net(Duration.ZERO, confirmed(cellular)),
                    TraceEvent.Net(Duration.ZERO, NetworkState.Offline),
                ),
                sink.events.toList(),
                "a monitor with no sequence has nothing to compute a gap from",
            )
        }

    /**
     * **The second generation.** A gap-free trace round-trips whether or not gaps are modelled at all, so
     * only this test can fail for the reason #315 exists: record a lossy ride, rebuild the script, replay
     * it, and record *that*. The two traces must be identical, gap lines included, and the replayed
     * monitor must report the same density — the original platform's, lost observations counted.
     *
     * The failure this pins down is silent by construction: a replay that bumped a count without jumping
     * the observation sequence produces a *contiguous* stream, so the second recording measures zero
     * drops between every pair and the gap simply evaporates, leaving a trace that reads as a quiet
     * network and a count with nothing behind it.
     */
    @Test
    fun aGappedRideSurvivesRecordReplayAndReRecording() =
        runTest {
            val gapped =
                NetworkMonitorScript(
                    capability = fullLadder,
                    initialState = confirmed(wifi),
                    transitions =
                        listOf(
                            NetworkMonitorScript.Transition(100.milliseconds, confirmed(wifi)),
                            NetworkMonitorScript.Transition(200.milliseconds, confirmed(cellular), droppedBefore = 36),
                            NetworkMonitorScript.Transition(300.milliseconds, NetworkState.Offline),
                        ),
                )
            val first = CapturingSink()
            val original = record(gapped, first)

            assertEquals(
                listOf<TraceEvent>(
                    TraceEvent.NetCapability(Duration.ZERO, fullLadder),
                    TraceEvent.Net(Duration.ZERO, confirmed(wifi)),
                    TraceEvent.Net(100.milliseconds, confirmed(wifi)),
                    TraceEvent.NetGap(200.milliseconds, dropped = 36),
                    TraceEvent.Net(200.milliseconds, confirmed(cellular)),
                    TraceEvent.Net(300.milliseconds, NetworkState.Offline),
                ),
                first.events.toList(),
                "the ride's own losses are in its trace",
            )

            val rebuilt = networkMonitorScriptFromTrace(first.events)
            assertEquals(gapped.transitions, rebuilt.transitions, "droppedBefore survives the trace")

            val second = CapturingSink()
            val replayed = record(rebuilt, second)

            assertEquals(first.events.toList(), second.events.toList(), "the second generation carries the same gap")
            assertEquals(
                original.observationCount.value,
                replayed.observationCount.value,
                "and the same density behind it",
            )
            assertEquals(39L, replayed.observationCount.value, "3 applied transitions + the 36 that were lost")
        }

    /**
     * A gap folds into the [TraceEvent.Net] that follows it even when another tap wrote between them —
     * the pairing question a standalone marker line raises. The fold walks the *network* events only, so
     * an interleaved QUIC line is skipped rather than breaking the association, and no tie-breaking on
     * equal `at` offsets is ever needed.
     */
    @Test
    fun aGapFoldsIntoTheFollowingTransitionAcrossInterleavedEvents() {
        val mixed =
            listOf(
                TraceEvent.NetCapability(Duration.ZERO, fullLadder),
                TraceEvent.Net(Duration.ZERO, confirmed(wifi)),
                TraceEvent.DgramOut(5_000.nanoseconds, len = 3, path = null, payloadHex = "abcdef"),
                TraceEvent.NetGap(1.seconds, dropped = 12),
                TraceEvent.State(6_000.nanoseconds, name = "Established", detail = null),
                TraceEvent.Net(1.seconds, confirmed(cellular)),
                TraceEvent.Net(2.seconds, NetworkState.Offline),
            )
        val script = networkMonitorScriptFromTrace(mixed)
        assertEquals(confirmed(wifi), script.initialState)
        assertEquals(
            listOf(
                NetworkMonitorScript.Transition(1.seconds, confirmed(cellular), droppedBefore = 12),
                NetworkMonitorScript.Transition(2.seconds, NetworkState.Offline),
            ),
            script.transitions,
        )
    }

    /**
     * Two gaps with no transition to attach to, both ignored rather than throwing:
     *  - **before the first state** — that state is the script's initial value, not a transition, and a
     *    subscriber that opened partway through lost nothing before it arrived;
     *  - **trailing** — the mark of a truncated capture (the recorder's job cancelled between the gap
     *    line and the observation it described). A truncated trace must still replay.
     */
    @Test
    fun aGapWithNoTransitionToAttachToIsIgnored() {
        val leading =
            listOf(
                TraceEvent.NetGap(Duration.ZERO, dropped = 4),
                TraceEvent.Net(Duration.ZERO, confirmed(wifi)),
                TraceEvent.Net(1.seconds, confirmed(cellular)),
            )
        assertEquals(
            listOf(NetworkMonitorScript.Transition(1.seconds, confirmed(cellular))),
            networkMonitorScriptFromTrace(leading).transitions,
            "the first state is the initial value, so a gap before it modifies nothing",
        )

        val truncated =
            listOf(
                TraceEvent.Net(Duration.ZERO, confirmed(wifi)),
                TraceEvent.Net(1.seconds, confirmed(cellular)),
                TraceEvent.NetGap(2.seconds, dropped = 7),
            )
        assertEquals(
            listOf(NetworkMonitorScript.Transition(1.seconds, confirmed(cellular))),
            networkMonitorScriptFromTrace(truncated).transitions,
            "a capture cut off after the gap line still replays",
        )
    }

    /** Every trace recorded before gap lines existed still means exactly what it meant. */
    @Test
    fun aTraceWithNoGapLinesParsesAsItAlwaysDid() {
        val old =
            listOf(
                TraceEvent.NetCapability(Duration.ZERO, fullLadder),
                TraceEvent.Net(Duration.ZERO, confirmed(wifi)),
                TraceEvent.Net(1.seconds, confirmed(cellular)),
                TraceEvent.Net(2.seconds, NetworkState.Offline),
            )
        assertEquals(
            listOf(
                NetworkMonitorScript.Transition(1.seconds, confirmed(cellular)),
                NetworkMonitorScript.Transition(2.seconds, NetworkState.Offline),
            ),
            networkMonitorScriptFromTrace(old).transitions,
        )
        assertTrue(
            networkMonitorScriptFromTrace(old).transitions.all { it.droppedBefore == 0L },
            "absent gap lines replay as a plain +1, never as a fabricated claim",
        )
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
