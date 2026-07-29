package com.ditchoom.socket

import com.ditchoom.socket.testkit.networkMonitorScriptFromTrace
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.weakestCapabilityFor
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Replays a network flap **captured from a physical Android device** on every platform, with no device
 * attached — the replay half of the loop `AndroidNetworkMonitorTraceCapture` records.
 *
 * The gap this closes: until now the only assertions about what real Android delivers lived in
 * `AndroidNetworkMonitorRobolectricTests`, where the event sequence is *hypothesized* — a shadow invokes
 * `onAvailable`/`onLost` in whatever order the test author wrote. The two instrumented tests assert
 * static seeded state, so nothing anywhere asserted that the monitor's state ever *changes* on real
 * hardware. This fixture is a recording of it changing.
 *
 * Because it is a [TraceEvent] trace, it costs nothing to keep honest: the fixture decodes through the
 * shipped parser, re-encodes to itself, and replays through the shipped [ScriptedNetworkMonitor]. A
 * consumer can do the same with their own capture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidDeviceFlapReplayTests {
    @Test
    fun theCapturedFlapDecodesToTheSequenceTheDeviceProduced() {
        val events = TraceEvent.parseAll(DEVICE_FLAP_V1)
        assertEquals(5, events.size, "the capture is 5 state edges — one per callback, not two per callback")

        // Round-trip: the fixture is exactly what the recorder emits, so re-encoding must be a fixpoint.
        // If this ever fails, the fixture was hand-edited rather than re-captured.
        assertEquals(DEVICE_FLAP_V1.trim(), events.joinToString("\n") { it.toString() })
    }

    @Test
    fun replayingTheDeviceFlapDrivesTheStateThroughEveryEdge() =
        runTest {
            val monitor = ScriptedNetworkMonitor(networkMonitorScriptFromTrace(TraceEvent.parseAll(DEVICE_FLAP_V1)))

            val seen = mutableListOf<NetworkState>()
            backgroundScope.launch { monitor.state.collect { seen += it } }
            runCurrent() // subscribe before playback, so no early transition is missed

            monitor.play()
            runCurrent() // flush the final transition's emission into the collector

            // Two full flaps: the Wi-Fi down/up cycle, then the airplane-mode cycle. This is the
            // assertion that did not exist anywhere before — that the state transitions at all.
            assertEquals(
                listOf(true, false, true, false, true),
                seen.map { it.canRouteOffLink },
                "the device went routable → offline → routable → offline → routable",
            )
            assertEquals(2, seen.count { it == NetworkState.Offline }, "both drops reported Offline")
        }

    @Test
    fun everyReconnectPublishesADistinctLinkIdentity() =
        runTest {
            val monitor = ScriptedNetworkMonitor(networkMonitorScriptFromTrace(TraceEvent.parseAll(DEVICE_FLAP_V1)))

            val seen = mutableListOf<NetworkState>()
            backgroundScope.launch { monitor.state.collect { seen += it } }
            runCurrent() // subscribe before playback, so no early transition is missed

            monitor.play()
            runCurrent() // flush the final transition's emission into the collector

            // The property QUIC auto-migration and the transport-fallback capability cache depend on:
            // reconnecting to the *same SSID* still yields a different NetworkId, because Android issues
            // a fresh networkHandle. A monitor that reported a stable identity across a flap would let a
            // stale per-network capability entry survive a genuine link change.
            val links = seen.map { it.networkId }.filterIsInstance<NetworkId.Link>()
            assertEquals(3, links.size, "three reconnects were captured")
            links.forEach { assertEquals(NetworkKind.Wifi, it.kind) }
            assertEquals(
                links.size,
                links.map { it.handle }.toSet().size,
                "each reconnect must carry a distinct networkHandle, got ${links.map { it.handle }}",
            )

            // And each drop clears identity rather than leaving the dead link published — for free now,
            // because Offline structurally cannot carry one.
            assertEquals(2, seen.count { it.networkId == NetworkId.Unidentified }, "both drops cleared the identity")
        }

    /**
     * The reason `pathChanges()` exists (RFC_NETWORK_REACHABILITY §9.4): a path-change consumer must see
     * exactly the identity edges, never a reachability transition on the same link.
     */
    @Test
    fun pathChangesSeesOnlyTheIdentityEdges() =
        runTest {
            val monitor = ScriptedNetworkMonitor(networkMonitorScriptFromTrace(TraceEvent.parseAll(DEVICE_FLAP_V1)))

            val changes = mutableListOf<NetworkId>()
            backgroundScope.launch { monitor.pathChanges().collect { changes += it } }
            runCurrent()

            monitor.play()
            runCurrent()

            // Four changes after the connect-time baseline is dropped: link → gone → link → gone → link.
            assertEquals(4, changes.size, "one emission per genuine identity change, got $changes")
            assertEquals(NetworkId.Unidentified, changes[0])
            assertIs<NetworkId.Link>(changes[1])
            assertEquals(NetworkId.Unidentified, changes[2])
            assertIs<NetworkId.Link>(changes[3])
        }

    @Test
    fun theScriptStartsInTheStateTheDeviceWasInWhenRecordingBegan() {
        val script = networkMonitorScriptFromTrace(TraceEvent.parseAll(DEVICE_FLAP_V1))

        val initial = script.initialState
        assertTrue(initial.canRouteOffLink, "recording began on a working Wi-Fi network")
        val id = initial.networkId
        assertIs<NetworkId.Link>(id)
        assertEquals(NetworkKind.Wifi, id.kind)
    }

    /**
     * The fixture carries no `NET_CAP` line (the recorder that produced it predated one), so the replay
     * derives the weakest capability that explains the whole timeline — which, because the capture
     * contains an [InternetAccess.Observed] verdict, is [ReachResolution.RouteAndInternet]: exactly what
     * `AndroidNetworkMonitor` declares.
     */
    @Test
    fun theDerivedCapabilityMatchesWhatAndroidDeclares() {
        val events = TraceEvent.parseAll(DEVICE_FLAP_V1)
        val script = networkMonitorScriptFromTrace(events)

        assertEquals(ReachResolution.RouteAndInternet, script.capability.resolution)
        assertEquals(
            weakestCapabilityFor(events.filterIsInstance<TraceEvent.Net>().map { it.state }),
            script.capability,
        )
        // A trace does not record whether the platform pushed or the monitor polled.
        assertEquals(MonitorMechanism.Unknown, script.capability.mechanism)
    }

    private companion object {
        /**
         * Captured 2026-07-29 on a Realme RMX3933 (Android 15 / API 35) over real Wi-Fi, by
         * `AndroidNetworkMonitorTraceCapture`. Two driven flaps:
         *
         *  - `t≈2.3s` Wi-Fi disabled → `t≈13.6s` Wi-Fi re-associated on a new handle
         *  - `t≈35.4s` airplane mode on → `t≈46.7s` off, re-associated on a third handle
         *
         * **Transcribed, not re-captured.** The recording predates the ladder: it was taken by the
         * two-flow recorder, whose `NET_AVAIL AVAILABLE` meant precisely `NET_CAPABILITY_INTERNET`
         * present, with `VALIDATED` never read. `Routable(id, Pending)` is that fact and no more —
         * "routes exist, validation not observed" — so nothing here claims a verdict the device never
         * gave. Each pair of interleaved availability/identity lines collapses to the single coherent
         * state their shared callback actually produced, stamped at the earlier of the two (the two
         * stamps differ only by the ~0.5ms the second collector was scheduled later).
         *
         * A re-capture on the current monitor will additionally show the ~0.7–1s
         * `Pending` → `Confirmed` window after each reassociation, which the old recorder could not see.
         * Regenerate rather than edit — see `AndroidNetworkMonitorTraceCapture` for the command.
         */
        val DEVICE_FLAP_V1 =
            """
            v1 50170269 NET Routable Link:Wifi:441492361229 Pending
            v1 2324165652 NET Offline
            v1 13649972336 NET Routable Link:Wifi:445787328525 Pending
            v1 35429576167 NET Offline
            v1 46677846544 NET Routable Link:Wifi:450082295821 Pending
            """.trimIndent()
    }
}
