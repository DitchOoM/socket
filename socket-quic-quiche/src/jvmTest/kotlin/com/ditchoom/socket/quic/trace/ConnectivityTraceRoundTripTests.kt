package com.ditchoom.socket.quic.trace

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.canRouteOffLink
import com.ditchoom.socket.networkId
import com.ditchoom.socket.quic.ImpairmentConfig
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.recordMissingNativeLib
import com.ditchoom.socket.quic.sim.Observed
import com.ditchoom.socket.quic.sim.SimEvent
import com.ditchoom.socket.quic.sim.SimNetworkMonitor
import com.ditchoom.socket.quic.sim.runQuicSim
import com.ditchoom.socket.quic.withSemanticSim
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import com.ditchoom.socket.transport.Liveness as TransportLiveness

/**
 * Round-trip proof for the connectivity taps (RFC_DETERMINISTIC_SIMULATION.md §5, coverage
 * follow-up to PR #225): a real-quiche session that records a **mid-flight connectivity event**
 * alongside QUIC traffic, is parsed back typed, converted through [TraceToFixture], and replayed
 * through the W2 [runQuicSim] engine — where the connectivity events must drive the sim's
 * `SimNetworkMonitor` / `SimLiveness` seams, not just the datagram path.
 *
 * This closes the loop the base PR left open: NET / NET_CAP / LIVENESS existed in the grammar and the
 * parser but nothing emitted them, and no test proved a captured connectivity event survives
 * capture → fixture → replay.
 */
class ConnectivityTraceRoundTripTests {
    @Test
    fun connectivity_events_captured_midflight_roundtrip_and_drive_the_monitor_seam() =
        runTest(timeout = 60.seconds) {
            val lines = mutableListOf<String>()
            val recorder = QuicTraceRecorder({ e -> lines += e.toString() })
            // The engine taps NetworkMonitor.availability/networkId; liveness is driven by the
            // transport seam above the engine (ReconnectingConnection), so its capture goes through
            // the recorder's wrap() — a probe records one LIVENESS input event.
            val liveness = recorder.wrap(TransportLiveness { TransportLiveness.Result.Dead })
            val migratedTo = NetworkId.Link(NetworkKind.Wifi, 7L)
            try {
                withSemanticSim(
                    // Lossless + zero latency: fully virtual-time (the W4-proven config), millisecond wall clock.
                    ImpairmentConfig(seed = 21L),
                    establishTimeout = 5.seconds,
                    clientRecorder = recorder,
                ) {
                    // A real echo exchange, so the connectivity events sit inside genuine QUIC traffic.
                    val serverJob =
                        launch {
                            val stream = server.acceptStream()
                            val data = stream.read(5.seconds)
                            if (data is ReadResult.Data) {
                                stream.write(data.buffer, 5.seconds)
                                data.buffer.freeIfNeeded()
                            }
                            stream.close()
                        }
                    val stream = client.openStream()
                    val sendBuf = BufferFactory.network().allocate(8)
                    sendBuf.writeString("trace me", Charset.UTF8)
                    sendBuf.resetForRead()
                    try {
                        stream.write(sendBuf, 5.seconds)
                    } finally {
                        sendBuf.freeNativeMemory()
                    }
                    val response = stream.read(5.seconds)
                    if (response is ReadResult.Data) response.buffer.freeIfNeeded()

                    // --- mid-flight connectivity events: the Wi-Fi path returns, then the link goes
                    // away entirely, plus a liveness probe — exactly the transport-layer signals a
                    // consumer taps. One value each, so neither can be recorded torn. ---
                    recorder.networkState(NetworkState.Routable(migratedTo, InternetAccess.Unobserved))
                    recorder.networkState(NetworkState.Offline)
                    liveness.probe()

                    stream.close()
                    serverJob.cancel()
                }
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(e)
            }

            // --- capture: connectivity events landed in the SAME trace as the QUIC traffic, typed. ---
            val events = TraceEvent.parseAll(lines)
            assertTrue(events.any { it is TraceEvent.DgramIn }, "expected QUIC traffic in the same trace")
            val capturedNets = events.filterIsInstance<TraceEvent.Net>()
            assertEquals(
                listOf(NetworkState.Routable(migratedTo, InternetAccess.Unobserved), NetworkState.Offline),
                capturedNets.map { it.state },
                "captured NET, in order and whole",
            )
            assertEquals(
                TransportLiveness.Result.Dead,
                events.filterIsInstance<TraceEvent.Liveness>().single().result,
                "captured LIVENESS via recorder.wrap",
            )

            // --- fixture codegen: the input subset (incl. all three connectivity events) maps onto
            // the W2 sim model — the codegen already handled NET_*/LIVENESS; assert nothing is lost. ---
            val inputs = events.filter { it.isInput }
            val fixture = TraceToFixture.toSimFixture("connectivity-replay", inputs)
            assertEquals(
                listOf(NetworkState.Routable(migratedTo, InternetAccess.Unobserved), NetworkState.Offline),
                fixture.events.filterIsInstance<SimEvent.Net>().map { it.state },
            )
            assertEquals(
                TransportLiveness.Result.Dead,
                fixture.events
                    .filterIsInstance<SimEvent.Liveness>()
                    .single()
                    .result,
            )

            // --- replay: the connectivity events drive the sim's NetworkMonitor seam. The Tier-A sim
            // scripts (but does not probe) liveness — reacting to a path change is the transport
            // layer's job, not the driver's (see GoldenDriverFixtures.datagramThenStalePath) — so the
            // asserted observations are the monitor's, and the liveness proof is the fixture mapping
            // above (SimEvent.Liveness present). ---
            val replay = runQuicSim(fixture, clientMode = true)
            assertEquals(
                listOf(NetworkState.Routable(migratedTo, InternetAccess.Unobserved), NetworkState.Offline),
                replay.trace.events
                    .filterIsInstance<Observed.NetworkChanged>()
                    .map { it.state },
                "captured NET events must drive SimNetworkMonitor.state on replay:\n${replay.trace.render()}",
            )
        }

    /**
     * Unit proof of the recorder's transport-seam taps in isolation (no quiche): [QuicTraceRecorder.observe]
     * collects a [com.ditchoom.socket.NetworkMonitor]'s current + changed availability/networkId into
     * the trace, and [QuicTraceRecorder.wrap] records each liveness probe outcome. This is the
     * mechanism the engine wires on the client connect path.
     */
    @Test
    fun observe_and_wrap_forward_the_transport_seams_into_the_trace() =
        runTest {
            val lines = mutableListOf<String>()
            val recorder = QuicTraceRecorder({ e -> lines += e.toString() })
            val monitor = SimNetworkMonitor.on(NetworkId.Unidentified)
            val job = recorder.observe(monitor, backgroundScope)
            runCurrent() // the collector subscribes → the current value is recorded

            val cellular = NetworkId.Link(NetworkKind.Cellular, 3L)
            monitor.set(NetworkState.Offline)
            runCurrent() // let the collector settle: one StateFlow conflates, so back-to-back sets drop one
            monitor.setNetworkId(cellular)
            runCurrent()
            job.cancel()

            val wrapped = recorder.wrap(TransportLiveness { TransportLiveness.Result.Alive })
            assertEquals(TransportLiveness.Result.Alive, wrapped.probe())

            val events = TraceEvent.parseAll(lines)
            val states = events.filterIsInstance<TraceEvent.Net>().map { it.state }
            assertTrue(
                states.first().canRouteOffLink && states.contains(NetworkState.Offline),
                "observe must record the current value and every change: $lines",
            )
            assertTrue(
                states.any { it.networkId == cellular },
                "observe must record the identity change: $lines",
            )
            assertEquals(
                monitor.capability,
                events.filterIsInstance<TraceEvent.NetCapability>().single().capability,
                "observe must record the monitor's declared capability once, up front: $lines",
            )
            assertEquals(
                TransportLiveness.Result.Alive,
                events.filterIsInstance<TraceEvent.Liveness>().single().result,
                "wrap must record the probe outcome",
            )
        }
}
