package com.ditchoom.socket.quic.trace

import com.ditchoom.socket.BlockReason
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.MonitorCapability
import com.ditchoom.socket.MonitorMechanism
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.ReachResolution
import com.ditchoom.socket.quic.sim.Observed
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceMigrationOutcome
import com.ditchoom.socket.testkit.trace.TraceMigrationTrigger
import com.ditchoom.socket.testkit.trace.TracePath
import com.ditchoom.socket.testkit.trace.TraceSilencePhase
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import com.ditchoom.socket.transport.Liveness as TransportLiveness

/**
 * The trace line grammar round trip (RFC_DETERMINISTIC_SIMULATION.md §5): every input and observation event survives
 * `parse(emit(e)) == e`, so a recording parses back typed.
 */
class TraceRecorderRoundTripTests {
    @Test
    fun every_input_event_type_roundtrips_through_the_line_format() {
        val events =
            listOf<TraceEvent>(
                TraceEvent.DgramIn(0L.nanoseconds, 4, null, "c0ffee00"),
                TraceEvent.DgramIn(1_000L.nanoseconds, 2, TracePath(4, 4433, 0L, 0x7f000001L), "beef"),
                TraceEvent.Error(2_000L.nanoseconds, "SimIoException", "ENETDOWN: network is down"),
                TraceEvent.Error(3_000L.nanoseconds, "QuicCloseException", ""),
                // Every rung of the ladder, and every InternetAccess verdict, through the line format.
                TraceEvent.Net(4_000L.nanoseconds, NetworkState.Unknown),
                TraceEvent.Net(5_000L.nanoseconds, NetworkState.Offline),
                TraceEvent.Net(6_000L.nanoseconds, NetworkState.LinkLocal(NetworkId.Unidentified)),
                TraceEvent.Net(
                    7_000L.nanoseconds,
                    NetworkState.Routable(NetworkId.KindOnly(NetworkKind.Cellular), InternetAccess.Unobserved),
                ),
                TraceEvent.Net(
                    8_000L.nanoseconds,
                    NetworkState.Routable(NetworkId.Link(NetworkKind.Wifi, -42L), InternetAccess.Observed.Confirmed),
                ),
                TraceEvent.Net(9_000L.nanoseconds, NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Observed.Pending)),
                TraceEvent.Net(10_000L.nanoseconds, NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Observed.Limited)),
                TraceEvent.Net(
                    11_000L.nanoseconds,
                    NetworkState.Routable(
                        NetworkId.Unidentified,
                        InternetAccess.Observed.Blocked(BlockReason.CaptivePortal),
                    ),
                ),
                TraceEvent.Net(
                    12_000L.nanoseconds,
                    NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Observed.Blocked(BlockReason.Suspended)),
                ),
                TraceEvent.Net(
                    13_000L.nanoseconds,
                    NetworkState.LinkLocal(
                        NetworkId.Link(
                            NetworkKind.Vpn(setOf(NetworkKind.Cellular, NetworkKind.Other("weird label (x, y): z"))),
                            7L,
                        ),
                    ),
                ),
                // A recorded observation-sequence gap: the losses that happened before the NET after it.
                TraceEvent.NetGap(13_500L.nanoseconds, dropped = 36),
                TraceEvent.NetCapability(
                    14_000L.nanoseconds,
                    MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteAndInternet),
                ),
                TraceEvent.NetCapability(
                    15_000L.nanoseconds,
                    MonitorCapability(MonitorMechanism.Polled(5.seconds), ReachResolution.LinkOnly),
                ),
                TraceEvent.Liveness(16_000L.nanoseconds, TransportLiveness.Result.Dead),
            )
        val lines = mutableListOf<String>()
        val recorder = QuicTraceRecorder({ e -> lines += e.toString() })
        events.forEach { recorder.record(it) }
        assertEquals(events, TraceEvent.parseAll(lines))
        // Observation events round-trip too (tooling reads whole traces back).
        val observations =
            listOf<TraceEvent>(
                TraceEvent.DgramOut(20_000L.nanoseconds, 3, null, "aabbcc"),
                TraceEvent.State(21_000L.nanoseconds, "Established", "h3"),
                TraceEvent.State(22_000L.nanoseconds, "Closed", "IdleTimeout (0x-1)"),
                TraceEvent.PathState(23_000L.nanoseconds, "Probing", "10.0.0.2", 4444),
                TraceEvent.PathState(24_000L.nanoseconds, "None", null, 0),
                // Both triggers, and outcomes from either side of the sealed hierarchy.
                TraceEvent.Migration(25_000L.nanoseconds, TraceMigrationTrigger.LinkChanged, 1, TraceMigrationOutcome.Succeeded),
                TraceEvent.Migration(
                    26_000L.nanoseconds,
                    TraceMigrationTrigger.PathStoppedAnswering,
                    7,
                    TraceMigrationOutcome.NoSpareConnectionId,
                ),
                // Every phase, including a tally only the patience ceiling could have declared.
                TraceEvent.Silence(27_000L.nanoseconds, TraceSilencePhase.None, 0, Duration.ZERO),
                TraceEvent.Silence(28_000L.nanoseconds, TraceSilencePhase.Building, 3, 1_500_000_000L.nanoseconds),
                TraceEvent.Silence(29_000L.nanoseconds, TraceSilencePhase.Declared, 4, 3_695_000_000L.nanoseconds),
                TraceEvent.Silence(30_000L.nanoseconds, TraceSilencePhase.Declared, 2, 4_000_000_000L.nanoseconds),
            )
        val obsLines = mutableListOf<String>()
        QuicTraceRecorder({ e -> obsLines += e.toString() }).also { r -> observations.forEach { r.record(it) } }
        assertEquals(observations, TraceEvent.parseAll(obsLines))
    }
}
