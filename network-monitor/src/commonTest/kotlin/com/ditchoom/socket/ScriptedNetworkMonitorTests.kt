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

    @Test
    fun reportsInitialStateBeforePlay() =
        runTest {
            val monitor =
                ScriptedNetworkMonitor(
                    networkMonitorScript(
                        initialAvailability = NetworkAvailability.UNAVAILABLE,
                        initialNetworkId = wifi,
                    ) {
                        after(1.seconds) { networkId(cellular) }
                    },
                )
            // Nothing played yet — the monitor sits at its initial state.
            assertEquals(NetworkAvailability.UNAVAILABLE, monitor.availability.value)
            assertEquals(wifi, monitor.networkId.value)
        }

    @Test
    fun playsTransitionsAtScheduledVirtualInstants() =
        runTest {
            val monitor =
                ScriptedNetworkMonitor(
                    networkMonitorScript(
                        initialAvailability = NetworkAvailability.AVAILABLE,
                        initialNetworkId = wifi,
                    ) {
                        after(1.seconds) { networkId(cellular) }
                        after(500.milliseconds) { availability(NetworkAvailability.UNAVAILABLE) }
                    },
                )

            val t0 = testScheduler.currentTime
            val idChanges = mutableListOf<Pair<Long, NetworkId>>()
            val availChanges = mutableListOf<Pair<Long, NetworkAvailability>>()
            backgroundScope.launch { monitor.networkId.collect { idChanges += (testScheduler.currentTime - t0) to it } }
            backgroundScope.launch { monitor.availability.collect { availChanges += (testScheduler.currentTime - t0) to it } }
            runCurrent() // collectors subscribed; both receive the initial value at t=0

            monitor.play()
            runCurrent() // flush the final transition's emission to the collectors

            // networkId: wifi@0, cellular@1s. availability: AVAILABLE@0, UNAVAILABLE@1.5s.
            assertEquals(listOf(0L to wifi, 1000L to cellular), idChanges.toList())
            assertEquals(
                listOf(0L to NetworkAvailability.AVAILABLE, 1500L to NetworkAvailability.UNAVAILABLE),
                availChanges.toList(),
            )
            assertEquals(cellular, monitor.networkId.value)
            assertEquals(NetworkAvailability.UNAVAILABLE, monitor.availability.value)
        }

    @Test
    fun deterministic50x() =
        runTest {
            val script =
                networkMonitorScript(initialNetworkId = wifi) {
                    after(1.seconds) { networkId(cellular) }
                    after(1.seconds) {
                        availability(NetworkAvailability.UNAVAILABLE)
                        networkId(wifi)
                    }
                }
            var golden: List<Pair<Long, String>>? = null
            repeat(50) {
                val trace = mutableListOf<Pair<Long, String>>()
                val monitor = ScriptedNetworkMonitor(script)
                val t0 = testScheduler.currentTime
                val job =
                    launch {
                        launch { monitor.networkId.collect { trace += (testScheduler.currentTime - t0) to "id=$it" } }
                        launch { monitor.availability.collect { trace += (testScheduler.currentTime - t0) to "avail=$it" } }
                    }
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
            val monitor = ScriptedNetworkMonitor(NetworkMonitorScript.steady(networkId = wifi))
            val before = testScheduler.currentTime
            monitor.play()
            assertEquals(before, testScheduler.currentTime, "an empty script must not advance virtual time")
            assertEquals(wifi, monitor.networkId.value)
            assertEquals(NetworkAvailability.AVAILABLE, monitor.availability.value)
        }

    @Test
    fun playInLaunchesPlaybackInScope() =
        runTest {
            val monitor =
                ScriptedNetworkMonitor(
                    networkMonitorScript(initialNetworkId = wifi) { after(1.seconds) { networkId(cellular) } },
                )
            val job = monitor.playIn(this)
            testScheduler.advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(cellular, monitor.networkId.value)
            assertTrue(job.isCompleted, "playback job completes once the script is exhausted")
        }

    @Test
    fun rejectsNonDecreasingSchedule() {
        assertFailsWith<IllegalArgumentException> {
            NetworkMonitorScript(
                NetworkAvailability.AVAILABLE,
                NetworkId.Unidentified,
                listOf(
                    NetworkMonitorScript.Transition.Network(2.seconds, cellular),
                    NetworkMonitorScript.Transition.Network(1.seconds, wifi),
                ),
            )
        }
    }

    @Test
    fun rejectsNegativeOffset() {
        assertFailsWith<IllegalArgumentException> {
            NetworkMonitorScript(
                NetworkAvailability.AVAILABLE,
                NetworkId.Unidentified,
                listOf(NetworkMonitorScript.Transition.Network(-(1.seconds), cellular)),
            )
        }
    }

    @Test
    fun dslAccumulatesOffsetsAndSortsTransitions() {
        val script =
            networkMonitorScript(initialNetworkId = wifi) {
                after(1.seconds) { networkId(cellular) }
                after(500.milliseconds) { availability(NetworkAvailability.UNAVAILABLE) }
                availabilityAt(200.milliseconds, NetworkAvailability.AVAILABLE)
            }
        // after() accumulates: cellular@1s, UNAVAILABLE@1.5s; the absolute availabilityAt@0.2s sorts first.
        assertEquals(
            listOf<NetworkMonitorScript.Transition>(
                NetworkMonitorScript.Transition.Availability(200.milliseconds, NetworkAvailability.AVAILABLE),
                NetworkMonitorScript.Transition.Network(1.seconds, cellular),
                NetworkMonitorScript.Transition.Availability(1500.milliseconds, NetworkAvailability.UNAVAILABLE),
            ),
            script.transitions,
        )
        assertEquals(1500.milliseconds, script.duration)
        assertEquals(Duration.ZERO, NetworkMonitorScript.steady().duration)
    }
}
