package com.ditchoom.socket.testsuite.harness

import com.ditchoom.buffer.Charset
import com.ditchoom.data.readBuffer
import com.ditchoom.data.writeString
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.connect
import com.ditchoom.socket.quic.runQuicTest
import com.ditchoom.socket.quic.scaled
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * W6 validation of the consumer-facing harness control plane: `withNetworkHarness`
 * resolves the controller's `/describe` manifest and drives real scenarios through
 * the typed accessors.
 *
 * Follows the harness discipline end to end:
 *  - **skip-on-unreachable** — with the docker stack down every test is a clean
 *    no-op (a printed skip line, never a failure);
 *  - **wall-clock runner** — [runQuicTest] (real dispatcher, no virtual-time
 *    fast-forwarding) because these tests do real network I/O;
 *  - deadlines are [scaled] by `QUIC_TEST_TIME_SCALE`, assertions are not.
 *
 * Published in `commonMain` (like the other suites in this module) so consumers can
 * subclass it from their own `commonTest` to validate their harness deployment.
 * In-repo it is materialized on every target by `NetworkHarnessTests` in `commonTest`.
 */
abstract class NetworkHarnessTestSuite {
    private suspend fun echoRoundTrip(
        endpoint: HarnessEndpoint,
        payload: String,
    ): String =
        ClientSocket.connect(
            port = endpoint.port,
            hostname = endpoint.host,
            config = TransportConfig(connectTimeout = 5.seconds.scaled),
        ) { socket ->
            socket.writeString(payload, Charset.UTF8, 5.seconds.scaled)
            val buf = socket.readBuffer(5.seconds.scaled)
            buf.readString(buf.remaining(), Charset.UTF8)
        }

    /** Manifest resolves and a payload echoes through the `echo()` accessor. */
    @Test
    fun manifestResolvesAndEchoRoundTrips() =
        runQuicTest {
            val payload = "w6-withNetworkHarness-echo"
            val ran =
                withNetworkHarness {
                    val endpoint = echo()
                    val echoed = echoRoundTrip(endpoint, payload)
                    assertTrue(
                        echoed.startsWith(payload),
                        "expected echoed payload to start with '$payload', got '$echoed'",
                    )
                }
            if (!ran) println("[NetworkHarnessTestSuite] harness down — manifestResolvesAndEchoRoundTrips skipped")
        }

    /**
     * An `impaired(latency = 200 ms)` round-trip is measurably slower than direct.
     * The latency toxic adds a fixed 200 ms to every downstream chunk, so asserting
     * `>= 150 ms` is conservative (immune to scheduler noise) without being vacuous
     * — the direct round-trip on loopback completes in single-digit milliseconds.
     */
    @Test
    fun impairedLatencyRoundTripMeasurablySlower() =
        runQuicTest(timeout = 30.seconds) {
            val ran =
                withNetworkHarness {
                    val direct = measureTime { echoRoundTrip(echo(), "w6-direct") }
                    var impairedElapsed: Duration = Duration.ZERO
                    impaired(latency = 200.milliseconds) { proxy ->
                        impairedElapsed = measureTime { echoRoundTrip(proxy, "w6-impaired") }
                    }
                    println("[NetworkHarnessTestSuite] direct=$direct impaired=$impairedElapsed")
                    assertTrue(
                        impairedElapsed >= 150.milliseconds,
                        "impaired(latency=200ms) round-trip took $impairedElapsed — expected >= 150ms " +
                            "(direct took $direct)",
                    )
                    assertTrue(
                        impairedElapsed > direct,
                        "impaired round-trip ($impairedElapsed) not slower than direct ($direct)",
                    )
                }
            if (!ran) println("[NetworkHarnessTestSuite] harness down — impairedLatencyRoundTrip skipped")
        }

    /**
     * Skip semantics are deterministic regardless of stack state: pointing at a port
     * nothing listens on must return `false` without running the block — and without
     * throwing. (TCP port 9 / discard is never bound on CI or dev machines.)
     */
    @Test
    fun skipsCleanlyWhenControllerUnreachable() =
        runQuicTest {
            var executed = false
            val ran =
                withNetworkHarness(controllerPort = 9) {
                    executed = true
                }
            assertFalse(ran, "withNetworkHarness claimed to run against a dead controller port")
            assertFalse(executed, "block executed despite unreachable controller")
        }
}
