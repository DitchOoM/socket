package com.ditchoom.socket.quic.trace

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.quic.ImpairmentConfig
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.sim.Observed
import com.ditchoom.socket.quic.sim.runQuicSim
import com.ditchoom.socket.quic.withSemanticSim
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import com.ditchoom.socket.transport.Liveness as TransportLiveness

/**
 * W3 round-trip proof (RFC_DETERMINISTIC_SIMULATION.md §5): record a real-quiche session →
 * assert the trace's shape and one-clock monotonicity → parse it back typed → `parse(emit(e)) ==
 * e` for the input-event subset → replay the extracted inputs through the W2 [runQuicSim] engine.
 *
 * The replay smoke is asserted STRUCTURALLY (it runs to completion; every recorded DGRAM_IN is
 * fed to the driver), not byte-for-byte against quiche state: per RFC §4 Tier A, recorded field
 * ciphertext cannot be re-decrypted by a fresh quiche (new keys), so replay drives the stub
 * backend, which reproduces driver-visible behavior — trajectory shape, never quiche internals.
 */
class TraceRecorderRoundTripTests {
    @Test
    fun semanticSim_trace_roundtrips_and_replays() =
        runTest(timeout = 60.seconds) {
            val lines = mutableListOf<String>()
            val recorder = QuicTraceRecorder({ line -> lines += line })
            try {
                withSemanticSim(
                    // Lossless + zero latency: a pure event cascade, fully virtual-time (the W4
                    // proven configuration) — so this test costs milliseconds of wall clock.
                    ImpairmentConfig(seed = 21L),
                    establishTimeout = 5.seconds,
                    clientRecorder = recorder,
                ) {
                    val payload = "trace me"
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
                    val sendBuf = BufferFactory.network().allocate(payload.length)
                    sendBuf.writeString(payload, Charset.UTF8)
                    sendBuf.resetForRead()
                    try {
                        stream.write(sendBuf, 5.seconds)
                    } finally {
                        sendBuf.freeNativeMemory()
                    }
                    val response = stream.read(5.seconds)
                    if (response is ReadResult.Data) response.buffer.freeIfNeeded()
                    stream.close()
                    serverJob.cancel()
                }
            } catch (e: UnsatisfiedLinkError) {
                assumeTrue("Native lib not available: ${e.message}", false)
            }

            // --- 1. Shape: the session must have produced every driver-side event class. ---
            assertTrue(lines.isNotEmpty(), "recorder captured nothing")
            assertTrue(lines.all { it.startsWith("v1 ") }, "every line is versioned")
            val events = QuicTraceParser.parse(lines)
            assertTrue(events.any { it is QuicTraceEvent.DgramOut }, "DGRAM_OUT missing:\n${lines.joinToString("\n")}")
            assertTrue(events.any { it is QuicTraceEvent.DgramIn }, "DGRAM_IN missing")
            assertTrue(
                events.any { it is QuicTraceEvent.State && it.name == "Handshaking" },
                "initial STATE Handshaking missing",
            )
            assertTrue(
                events.any { it is QuicTraceEvent.State && it.name == "Established" },
                "STATE Established missing",
            )
            assertTrue(events.any { it is QuicTraceEvent.Stats }, "STATS missing (teardown snapshot guarantees one)")
            val stats = events.filterIsInstance<QuicTraceEvent.Stats>().last().stats
            assertTrue(stats.sent > 0 && stats.recv > 0, "terminal STATS must show traffic: $stats")
            // Recorded DGRAM payloads carry their bytes as hex, lengths consistent.
            events.filterIsInstance<QuicTraceEvent.DgramIn>().forEach {
                assertEquals(it.len * 2, it.payloadHex.length, "hex length mismatch on $it")
            }

            // --- 2. One clock: timestamps are monotonically non-decreasing in emit order. ---
            lines
                .map { QuicTraceParser.parseLine(it).atNanos }
                .zipWithNext()
                .forEach { (a, b) -> assertTrue(b >= a, "timestamps regressed: $a -> $b") }

            // --- 3. Round-trip: parse(emit(e)) == e for the input-event subset. ---
            val inputs = events.filter { it.isInput }
            assertTrue(inputs.filterIsInstance<QuicTraceEvent.DgramIn>().isNotEmpty(), "no replayable inputs recorded")
            val reEmitted = mutableListOf<String>()
            val reEncoder = QuicTraceRecorder({ line -> reEmitted += line })
            inputs.forEach { reEncoder.record(it) } // record() emits pre-stamped events verbatim
            assertEquals(inputs, QuicTraceParser.parse(reEmitted), "parse(emit(events)) != events")

            // --- 4. Replay smoke: the extracted inputs drive the W2 engine to completion. ---
            val fixture = TraceToFixture.toSimFixture("semantic-sim-replay", inputs)
            val replay = runQuicSim(fixture, clientMode = true)
            val fedCount = replay.trace.events.count { it is Observed.DatagramFed }
            assertEquals(
                inputs.count { it is QuicTraceEvent.DgramIn },
                fedCount,
                "replay must feed every recorded DGRAM_IN to the driver:\n${replay.trace.render()}",
            )
        }

    @Test
    fun every_input_event_type_roundtrips_through_the_line_format() {
        val events =
            listOf<QuicTraceEvent>(
                QuicTraceEvent.DgramIn(0L, 4, null, "c0ffee00"),
                QuicTraceEvent.DgramIn(1_000L, 2, PathKey(4, 4433, 0L, 0x7f000001L), "beef"),
                QuicTraceEvent.Error(2_000L, "SimIoException", "ENETDOWN: network is down"),
                QuicTraceEvent.Error(3_000L, "QuicCloseException", ""),
                QuicTraceEvent.NetAvail(4_000L, NetworkAvailability.UNAVAILABLE),
                QuicTraceEvent.Net(5_000L, NetworkId.Unidentified),
                QuicTraceEvent.Net(6_000L, NetworkId.KindOnly(NetworkKind.Cellular)),
                QuicTraceEvent.Net(7_000L, NetworkId.Link(NetworkKind.Wifi, -42L)),
                QuicTraceEvent.Net(
                    8_000L,
                    NetworkId.Link(NetworkKind.Vpn(setOf(NetworkKind.Cellular, NetworkKind.Other("weird label (x, y): z"))), 7L),
                ),
                QuicTraceEvent.Liveness(9_000L, TransportLiveness.Result.Dead),
            )
        val lines = mutableListOf<String>()
        val recorder = QuicTraceRecorder({ line -> lines += line })
        events.forEach { recorder.record(it) }
        assertEquals(events, QuicTraceParser.parse(lines))
        // Observation events round-trip too (tooling reads whole traces back).
        val observations =
            listOf<QuicTraceEvent>(
                QuicTraceEvent.DgramOut(10_000L, 3, null, "aabbcc"),
                QuicTraceEvent.State(11_000L, "Established", "h3"),
                QuicTraceEvent.State(12_000L, "Closed", "IdleTimeout (0x-1)"),
                QuicTraceEvent.PathState(13_000L, "Probing", "10.0.0.2", 4444),
                QuicTraceEvent.PathState(14_000L, "None", null, 0),
            )
        val obsLines = mutableListOf<String>()
        QuicTraceRecorder({ line -> obsLines += line }).also { r -> observations.forEach { r.record(it) } }
        assertEquals(observations, QuicTraceParser.parse(obsLines))
    }

    @Test
    fun generated_fixture_source_uses_the_simFixture_dsl() {
        val inputs =
            listOf<QuicTraceEvent>(
                QuicTraceEvent.DgramIn(0L, 3, null, "010203"),
                QuicTraceEvent.NetAvail(1_000_000L, NetworkAvailability.UNAVAILABLE),
                QuicTraceEvent.Net(2_000_000L, NetworkId.KindOnly(NetworkKind.Cellular)),
                QuicTraceEvent.Liveness(3_000_000L, TransportLiveness.Result.Dead),
                QuicTraceEvent.Error(4_000_000L, "SimIoException", "ENETDOWN"),
                // Observations must be dropped by codegen:
                QuicTraceEvent.DgramOut(5_000_000L, 1, null, "ff"),
                QuicTraceEvent.State(6_000_000L, "Closed", null),
            )
        val source = TraceToFixture.generateKotlin("field-capture-1", "fieldCapture1", inputs)
        val expectedFragments =
            listOf(
                "package com.ditchoom.socket.quic.sim.fixtures",
                "import com.ditchoom.socket.quic.sim.SimError",
                "import com.ditchoom.socket.quic.sim.simFixture",
                "internal val fieldCapture1: SimFixture =",
                "simFixture(\"field-capture-1\") {",
                "at(0.nanoseconds) datagramIn \"010203\"",
                "at(1000000.nanoseconds) availability NetworkAvailability.UNAVAILABLE",
                "at(2000000.nanoseconds) network NetworkId.KindOnly(NetworkKind.Cellular)",
                "at(3000000.nanoseconds) liveness Liveness.Result.Dead",
                "at(4000000.nanoseconds) recvError SimError(\"SimIoException: ENETDOWN\")",
                "runFor(4000000.nanoseconds)",
            )
        expectedFragments.forEach { fragment ->
            assertTrue(fragment in source, "generated source missing <$fragment>:\n$source")
        }
        assertTrue("DGRAM_OUT" !in source && "datagramOut" !in source && "\"ff\"" !in source, "observations leaked into fixture:\n$source")
        assertTrue("State" !in source, "STATE observation leaked into fixture:\n$source")
    }
}
