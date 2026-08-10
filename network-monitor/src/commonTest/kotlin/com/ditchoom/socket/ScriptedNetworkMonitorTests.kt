@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScriptedNetworkMonitorTests {
    private val wifi: NetworkId = NetworkId.Link(NetworkKind.Wifi, handle = 1)
    private val cellular: NetworkId = NetworkId.Link(NetworkKind.Cellular, handle = 2)

    private val fullLadder = MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteAndInternet)
    private val routeOnly = MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteOnly)

    private fun confirmed(id: NetworkId) = NetworkState.Routable(id, InternetAccess.Observed.Confirmed)

    private fun pending(id: NetworkId) = NetworkState.Routable(id, InternetAccess.Observed.Pending)

    @Test
    fun reportsInitialStateBeforePlay() =
        runTest {
            val monitor =
                ScriptedNetworkMonitor(
                    networkMonitorScript(fullLadder, initialState = NetworkState.Offline) {
                        after(1.seconds) { state(confirmed(cellular)) }
                    },
                )
            // Nothing played yet — the monitor sits at its initial state.
            assertEquals(NetworkState.Offline, monitor.state.value)
            assertEquals(NetworkId.Unidentified, monitor.state.value.networkId)
        }

    @Test
    fun reportsTheScriptsDeclaredCapability() =
        runTest {
            val monitor = ScriptedNetworkMonitor(NetworkMonitorScript.steady(NetworkState.Offline, routeOnly))
            assertEquals(routeOnly, monitor.capability)
        }

    @Test
    fun playsTransitionsAtScheduledVirtualInstants() =
        runTest {
            val monitor =
                ScriptedNetworkMonitor(
                    networkMonitorScript(fullLadder, initialState = confirmed(wifi)) {
                        after(1.seconds) { state(confirmed(cellular)) }
                        after(500.milliseconds) { state(NetworkState.Offline) }
                    },
                )

            val t0 = testScheduler.currentTime
            val seen = mutableListOf<Pair<Long, NetworkState>>()
            backgroundScope.launch { monitor.state.collect { seen += (testScheduler.currentTime - t0) to it } }
            runCurrent() // collector subscribed; receives the initial value at t=0

            monitor.play()
            runCurrent() // flush the final transition's emission to the collector

            assertEquals(
                listOf(
                    0L to confirmed(wifi),
                    1000L to confirmed(cellular),
                    1500L to NetworkState.Offline,
                ),
                seen.toList(),
            )
            assertEquals(NetworkState.Offline, monitor.state.value)
        }

    /**
     * The counter preserves the one dimension [NetworkMonitor.state] is deliberately lossy in: a
     * transition to the already-published state is invisible to every state collector (StateFlow
     * de-dupes it) but is still one platform observation — the callback burst a weak radio produces
     * before any rung changes. A script that repeats a state models exactly that chatter, so a
     * consumer's density logic (flap early-warning) is testable under virtual time.
     */
    @Test
    fun observationCountBumpsPerTransitionEvenWhenTheStateFlowDedupes() =
        runTest {
            val monitor =
                ScriptedNetworkMonitor(
                    networkMonitorScript(fullLadder, initialState = confirmed(wifi)) {
                        after(1.seconds) { state(confirmed(wifi)) } // chatter: folds to the same state
                        after(1.seconds) { state(confirmed(wifi)) } // chatter
                        after(1.seconds) { state(pending(cellular)) } // the real change
                    },
                )
            val stateEmissions = mutableListOf<NetworkState>()
            backgroundScope.launch { monitor.state.collect { stateEmissions += it } }
            runCurrent()

            assertEquals(0L, monitor.observationCount.value, "nothing observed before playback")
            monitor.play()
            runCurrent()

            assertEquals(3L, monitor.observationCount.value, "every applied transition is one observation")
            assertEquals(
                listOf<NetworkState>(confirmed(wifi), pending(cellular)),
                stateEmissions.toList(),
                "the state flow correctly de-dupes the chatter the counter preserves",
            )
        }

    /**
     * A scripted gap replays as a **sequence jump**, not as a bumped side-channel count: the stream a
     * consumer collects carries the survivors' real positions, so re-measuring it
     * ([ObservationSequence.droppedSince]) yields exactly the scripted `droppedBefore` — the property a
     * second-generation recording of this ride depends on (issue #315). The density counter follows for
     * free, because it is assigned from the same sequence.
     */
    @Test
    fun playReplaysAScriptedGapAsASequenceJumpTheStreamCanMeasureBack() =
        runTest {
            val monitor =
                ScriptedNetworkMonitor(
                    networkMonitorScript(fullLadder, initialState = confirmed(wifi)) {
                        after(1.seconds) { state(confirmed(wifi)) }
                        after(1.seconds) { state(pending(cellular), droppedBefore = 36) }
                        after(1.seconds) { state(confirmed(cellular)) }
                    },
                )
            val observed = mutableListOf<NetworkObservation>()
            backgroundScope.launch { monitor.observations.collect { observed += it } }
            runCurrent()

            monitor.play()
            runCurrent()

            val sequences = observed.map { (it as NetworkObservation.Sequenced).sequence }
            assertEquals(
                listOf(0L, 1L, 38L, 39L),
                sequences.map { it.value },
                "the subscription value, one transition, then the jump over the 36 lost observations",
            )
            assertEquals(36L, sequences[2].droppedSince(sequences[1]), "a replayed gap is measurable as the gap it was")
            assertEquals(
                39L,
                monitor.observationCount.value,
                "3 applied transitions + the 36 the platform made that this stream never saw",
            )
        }

    /** A negative gap is not a lossy timeline; the script rejects it where it is written. */
    @Test
    fun rejectsANegativeDroppedCount() {
        assertFailsWith<IllegalArgumentException> {
            NetworkMonitorScript(
                capability = fullLadder,
                initialState = confirmed(wifi),
                transitions = listOf(NetworkMonitorScript.Transition(1.seconds, confirmed(cellular), droppedBefore = -1)),
            )
        }
    }

    /**
     * The one flow is why a recording cannot interleave out of order any more: a state and its identity
     * arrive as a single emission, so there is no second stream to race (RFC_NETWORK_REACHABILITY §1.2).
     */
    @Test
    fun stateAndIdentityAlwaysArriveTogether() =
        runTest {
            val monitor =
                ScriptedNetworkMonitor(
                    networkMonitorScript(fullLadder, initialState = confirmed(wifi)) {
                        after(1.seconds) { state(NetworkState.Offline) }
                        after(1.seconds) { state(confirmed(cellular)) }
                    },
                )
            val pairs = mutableListOf<Pair<Boolean, NetworkId>>()
            backgroundScope.launch { monitor.state.collect { pairs += it.canRouteOffLink to it.networkId } }
            runCurrent()
            monitor.play()
            runCurrent()

            // Offline can never be seen beside a live identity, and a routable state can never be seen
            // beside Unidentified on a monitor that identifies its links.
            assertEquals(
                listOf(
                    true to wifi,
                    false to NetworkId.Unidentified,
                    true to cellular,
                ),
                pairs.toList(),
            )
        }

    @Test
    fun deterministic50x() =
        runTest {
            val script =
                networkMonitorScript(fullLadder, initialState = confirmed(wifi)) {
                    after(1.seconds) { state(pending(cellular)) }
                    after(1.seconds) { state(NetworkState.Offline) }
                }
            var golden: List<Pair<Long, String>>? = null
            repeat(50) {
                val trace = mutableListOf<Pair<Long, String>>()
                val monitor = ScriptedNetworkMonitor(script)
                val t0 = testScheduler.currentTime
                val job = launch { monitor.state.collect { trace += (testScheduler.currentTime - t0) to "$it" } }
                runCurrent()
                monitor.play()
                runCurrent() // flush the final transition before snapshotting the trace
                job.cancel()
                val expected = golden
                if (expected == null) {
                    golden = trace.toList()
                } else {
                    assertEquals(expected, trace.toList(), "run $it diverged")
                }
            }
        }

    @Test
    fun emptyScriptNeverChangesAndPlayReturnsImmediately() =
        runTest {
            val monitor = ScriptedNetworkMonitor(NetworkMonitorScript.steady())
            val before = testScheduler.currentTime
            monitor.play()
            assertEquals(before, testScheduler.currentTime, "an empty script must not advance virtual time")
            assertEquals(
                NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Unobserved),
                monitor.state.value,
            )
        }

    @Test
    fun playInLaunchesPlaybackInScope() =
        runTest {
            val monitor =
                ScriptedNetworkMonitor(
                    networkMonitorScript(fullLadder, initialState = confirmed(wifi)) {
                        after(1.seconds) { state(confirmed(cellular)) }
                    },
                )
            val job = monitor.playIn(this)
            testScheduler.advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(confirmed(cellular), monitor.state.value)
            assertTrue(job.isCompleted, "playback job completes once the script is exhausted")
        }

    @Test
    fun rejectsNonDecreasingSchedule() {
        assertFailsWith<IllegalArgumentException> {
            NetworkMonitorScript(
                fullLadder,
                NetworkState.Offline,
                listOf(
                    NetworkMonitorScript.Transition(2.seconds, confirmed(cellular)),
                    NetworkMonitorScript.Transition(1.seconds, confirmed(wifi)),
                ),
            )
        }
    }

    @Test
    fun rejectsNegativeOffset() {
        assertFailsWith<IllegalArgumentException> {
            NetworkMonitorScript(
                fullLadder,
                NetworkState.Offline,
                listOf(NetworkMonitorScript.Transition(-(1.seconds), confirmed(cellular))),
            )
        }
    }

    // --- the capability pairing rules, enforced where they fail cheapest -------------------------

    @Test
    fun rejectsAnObservedVerdictFromARouteOnlyMonitor() {
        // Apple/JVM/kernel-Linux never probe reachability. A fixture claiming they reported VALIDATED is
        // a bug in the fixture, and this is where it surfaces — no device required.
        val failure =
            assertFailsWith<IllegalArgumentException> {
                networkMonitorScript(routeOnly, initialState = NetworkState.Offline) {
                    after(1.seconds) { state(confirmed(wifi)) }
                }
            }
        assertTrue(
            failure.message!!.contains("RouteOnly"),
            "the failure must name the resolution that could not have produced it: ${failure.message}",
        )
    }

    @Test
    fun rejectsUnobservedFromAFullLadderMonitor() {
        // The converse: Android always has a verdict, so Unobserved is equally unproducible there.
        assertFailsWith<IllegalArgumentException> {
            networkMonitorScript(fullLadder, initialState = NetworkState.Offline) {
                after(1.seconds) { state(NetworkState.Routable(wifi, InternetAccess.Unobserved)) }
            }
        }
    }

    @Test
    fun rejectsRoutableAndLinkLocalFromALinkOnlyMonitor() {
        val linkOnly = MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.LinkOnly)
        // Asserting LinkLocal requires route visibility, which a browser/Node monitor does not have.
        assertFailsWith<IllegalArgumentException> {
            networkMonitorScript(linkOnly, initialState = NetworkState.LinkLocal(wifi)) {}
        }
        // But the optimistic rung with no verdict is exactly what it does report.
        val ok =
            networkMonitorScript(linkOnly, initialState = NetworkState.Offline) {
                after(1.seconds) { state(NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Unobserved)) }
            }
        assertEquals(1, ok.transitions.size)
    }

    @Test
    fun rejectsTransitionsOnAStaticMonitor() {
        // AlwaysAvailable's shape: it asserts one value and never looks again.
        assertFailsWith<IllegalArgumentException> {
            networkMonitorScript(
                MonitorCapability(MonitorMechanism.Static, ReachResolution.Asserted),
                initialState = NetworkState.Offline,
            ) {
                after(1.seconds) { state(confirmed(wifi)) }
            }
        }
    }

    @Test
    fun rejectsAnInitialStateTheCapabilityCannotProduce() {
        assertFailsWith<IllegalArgumentException> {
            NetworkMonitorScript(routeOnly, confirmed(wifi), emptyList())
        }
    }

    @Test
    fun dslAccumulatesOffsetsInCallOrder() {
        val script =
            networkMonitorScript(fullLadder, initialState = confirmed(wifi)) {
                after(1.seconds) { state(pending(cellular)) }
                after(500.milliseconds) { state(NetworkState.Offline) }
                stateAt(2.seconds, confirmed(cellular))
            }
        // after() accumulates: pending@1s, Offline@1.5s; stateAt() lands at its absolute offset and
        // advances the cursor — the built list is exactly the call order, never a re-sort of it.
        assertEquals(
            listOf(
                NetworkMonitorScript.Transition(1.seconds, pending(cellular)),
                NetworkMonitorScript.Transition(1500.milliseconds, NetworkState.Offline),
                NetworkMonitorScript.Transition(2.seconds, confirmed(cellular)),
            ),
            script.transitions,
        )
        assertEquals(2.seconds, script.duration)
        assertEquals(Duration.ZERO, NetworkMonitorScript.steady().duration)
    }

    @Test
    fun dslRejectsAStateAtBehindTheRunningOffset() {
        // The DSL enforces ordering at the call site rather than sorting it away, so both construction
        // paths (DSL and hand-built lists) answer "is out-of-order rejected?" the same way.
        val failure =
            assertFailsWith<IllegalArgumentException> {
                networkMonitorScript(fullLadder, initialState = confirmed(wifi)) {
                    after(1.seconds) { state(pending(cellular)) }
                    stateAt(200.milliseconds, confirmed(cellular))
                }
            }
        assertTrue(
            failure.message!!.contains("non-decreasing"),
            "the failure must state the ordering rule: ${failure.message}",
        )
    }

    /**
     * RFC_NETWORK_REACHABILITY §7.1 — the captive-portal timeline the device capture could **not**
     * reproduce (Realme overrides the connectivity-probe URLs; blackholed URLs still validated), and a
     * deterministic virtual-time test here.
     */
    @Test
    fun portalThenLoginIsReachableWithoutHardware() =
        runTest {
            val portal = NetworkState.Routable(wifi, InternetAccess.Observed.Blocked(BlockReason.CaptivePortal))
            val monitor =
                ScriptedNetworkMonitor(
                    networkMonitorScript(fullLadder, initialState = NetworkState.Unknown) {
                        after(Duration.ZERO) { state(pending(wifi)) }
                        after(800.milliseconds) { state(portal) }
                        after(30.seconds) { state(confirmed(wifi)) }
                    },
                )
            val seen = mutableListOf<Pair<Long, NetworkState>>()
            val t0 = testScheduler.currentTime
            backgroundScope.launch { monitor.state.collect { seen += (testScheduler.currentTime - t0) to it } }
            runCurrent()
            monitor.play()
            runCurrent()

            assertEquals(
                listOf(
                    0L to NetworkState.Unknown,
                    0L to pending(wifi),
                    800L to portal,
                    30_800L to confirmed(wifi),
                ),
                seen.toList(),
            )
            // The predicates a consumer actually branches on, over that timeline.
            assertEquals(
                listOf(
                    // Unknown: don't know yet — wait rather than declare failure.
                    Triple(false, false, true),
                    // Pending: attempt, and expect it to resolve on its own.
                    Triple(true, false, true),
                    // Blocked(CaptivePortal): retrying is futile; a human must intervene.
                    Triple(false, true, false),
                    // Confirmed.
                    Triple(true, false, false),
                ),
                seen.map { (_, s) -> Triple(s.canRouteOffLink, s.needsUserAction, s.isTransient) },
            )
        }
}
