package com.ditchoom.socket

import com.ditchoom.socket.testkit.networkMonitorScriptFromTrace
import com.ditchoom.socket.testkit.trace.TraceEvent
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
 * static seeded state, so nothing anywhere asserted that `availability` or `networkId` ever *changes* on
 * real hardware. This fixture is a recording of them changing.
 *
 * Because it is a [TraceEvent] trace, it costs nothing to keep honest: the fixture is the exact `v1`
 * text the shipped [NetworkMonitorRecorder][com.ditchoom.socket.testkit.NetworkMonitorRecorder] emitted,
 * it decodes through the shipped parser, and it replays through the shipped
 * [ScriptedNetworkMonitor]. A consumer can do the same with their own capture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidDeviceFlapReplayTests {
    @Test
    fun theCapturedFlapDecodesToTheSequenceTheDeviceProduced() {
        val events = TraceEvent.parseAll(DEVICE_FLAP_V1)
        assertEquals(10, events.size, "the capture is 5 availability edges interleaved with 5 identity edges")

        // Round-trip: the fixture is exactly what the recorder emits, so re-encoding must be a fixpoint.
        // If this ever fails, the fixture was hand-edited rather than re-captured.
        assertEquals(DEVICE_FLAP_V1.trim(), events.joinToString("\n") { it.toString() })
    }

    @Test
    fun replayingTheDeviceFlapDrivesAvailabilityThroughEveryEdge() =
        runTest {
            val monitor = ScriptedNetworkMonitor(networkMonitorScriptFromTrace(TraceEvent.parseAll(DEVICE_FLAP_V1)))

            val seen = mutableListOf<NetworkAvailability>()
            backgroundScope.launch { monitor.availability.collect { seen += it } }
            runCurrent() // subscribe before playback, so no early transition is missed

            monitor.play()
            runCurrent() // flush the final transition's emission into the collector

            // Two full flaps: the Wi-Fi down/up cycle, then the airplane-mode cycle. This is the
            // assertion that did not exist anywhere before — that availability transitions at all.
            assertEquals(
                listOf(
                    NetworkAvailability.AVAILABLE,
                    NetworkAvailability.UNAVAILABLE,
                    NetworkAvailability.AVAILABLE,
                    NetworkAvailability.UNAVAILABLE,
                    NetworkAvailability.AVAILABLE,
                ),
                seen,
            )
        }

    @Test
    fun everyReconnectPublishesADistinctLinkIdentity() =
        runTest {
            val monitor = ScriptedNetworkMonitor(networkMonitorScriptFromTrace(TraceEvent.parseAll(DEVICE_FLAP_V1)))

            val seen = mutableListOf<NetworkId>()
            backgroundScope.launch { monitor.networkId.collect { seen += it } }
            runCurrent() // subscribe before playback, so no early transition is missed

            monitor.play()
            runCurrent() // flush the final transition's emission into the collector

            // The property QUIC auto-migration and the transport-fallback capability cache depend on:
            // reconnecting to the *same SSID* still yields a different NetworkId, because Android issues
            // a fresh networkHandle. A monitor that reported a stable identity across a flap would let a
            // stale per-network capability entry survive a genuine link change.
            val links = seen.filterIsInstance<NetworkId.Link>()
            assertEquals(3, links.size, "three reconnects were captured")
            links.forEach { assertEquals(NetworkKind.Wifi, it.kind) }
            assertEquals(
                links.size,
                links.map { it.handle }.toSet().size,
                "each reconnect must carry a distinct networkHandle, got ${links.map { it.handle }}",
            )

            // And each drop clears identity rather than leaving the dead link published.
            assertTrue(seen.count { it == NetworkId.Unidentified } == 2, "both drops cleared the identity")
        }

    @Test
    fun theScriptStartsInTheStateTheDeviceWasInWhenRecordingBegan() {
        val script = networkMonitorScriptFromTrace(TraceEvent.parseAll(DEVICE_FLAP_V1))

        assertEquals(NetworkAvailability.AVAILABLE, script.initialAvailability)
        val initial = script.initialNetworkId
        assertIs<NetworkId.Link>(initial)
        assertEquals(NetworkKind.Wifi, initial.kind)
    }

    private companion object {
        /**
         * Captured 2026-07-29 on a Realme RMX3933 (Android 15 / API 35) over real Wi-Fi, by
         * `AndroidNetworkMonitorTraceCapture`. Two driven flaps:
         *
         *  - `t≈2.3s` Wi-Fi disabled → `t≈13.6s` Wi-Fi re-associated on a new handle
         *  - `t≈35.4s` airplane mode on → `t≈46.7s` off, re-associated on a third handle
         *
         * Regenerate rather than edit — see `AndroidNetworkMonitorTraceCapture` for the command.
         */
        val DEVICE_FLAP_V1 =
            """
            v1 50170269 NET_AVAIL AVAILABLE
            v1 50602654 NET_ID Link:Wifi:441492361229
            v1 2324165652 NET_AVAIL UNAVAILABLE
            v1 2324678191 NET_ID Unidentified
            v1 13649972336 NET_AVAIL AVAILABLE
            v1 13651066875 NET_ID Link:Wifi:445787328525
            v1 35429576167 NET_AVAIL UNAVAILABLE
            v1 35429711629 NET_ID Unidentified
            v1 46677846544 NET_AVAIL AVAILABLE
            v1 46680825967 NET_ID Link:Wifi:450082295821
            """.trimIndent()
    }
}
