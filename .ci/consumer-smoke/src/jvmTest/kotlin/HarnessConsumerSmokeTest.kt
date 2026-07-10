package consumer.smoke

import com.ditchoom.buffer.Charset
import com.ditchoom.data.readBuffer
import com.ditchoom.data.writeString
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.connect
import com.ditchoom.socket.testsuite.harness.withNetworkHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * W7 (RFC_DETERMINISTIC_SIMULATION §8): drive the container harness through the PUBLISHED
 * `com.ditchoom:socket-testsuite` artifact exactly as a downstream consumer would — a
 * `testImplementation` dependency resolved from the merged repo under validation, plain test code,
 * no docker CLI, no knowledge of `harness.env`.
 *
 * Two behaviours, both load-bearing for the consumer story:
 *  1. [echoRoundTripThroughPublishedHarnessApi] — `withNetworkHarness` resolves the controller's
 *     `/describe` manifest and a payload round-trips through the `echo()` scenario. The CI job
 *     brings up the echo+controller subset of the stack, so this genuinely RUNS there; with the
 *     stack down it exercises the documented skip path instead (one loud line, never a failure).
 *  2. [skipsCleanlyWhenControllerDown] — the skip-on-unreachable contract itself: a dead controller
 *     port must return `false` without running the block and without throwing. This is the
 *     guarantee that lets consumers ship harness-backed tests that stay green on machines with no
 *     docker at all.
 *
 * Same runner pattern as [LoopbackSmokeTest]: `runTest` for the overall deadline, real I/O hopped
 * onto Dispatchers.Default so virtual time never interacts with actual network reads.
 */
class HarnessConsumerSmokeTest {
    @Test
    fun echoRoundTripThroughPublishedHarnessApi() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                val payload = "consumer-smoke-w7"
                val ran =
                    withNetworkHarness {
                        val endpoint = echo()
                        val echoed =
                            ClientSocket.connect(
                                port = endpoint.port,
                                hostname = endpoint.host,
                                config = TransportConfig(connectTimeout = 5.seconds),
                            ) { socket ->
                                socket.writeString(payload, Charset.UTF8, 5.seconds)
                                val buf = socket.readBuffer(5.seconds)
                                buf.readString(buf.remaining(), Charset.UTF8)
                            }
                        assertTrue(
                            echoed.startsWith(payload),
                            "expected harness echo to return '$payload', got '$echoed'",
                        )
                        println("[consumer-smoke] harness echo OK — round-trip via published socket-testsuite")
                    }
                if (!ran) {
                    println(
                        "[consumer-smoke] harness SKIP: controller unreachable — " +
                            "echo round-trip skipped via withNetworkHarness's skip-on-unreachable",
                    )
                }
            }
        }

    @Test
    fun skipsCleanlyWhenControllerDown() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                var executed = false
                // TCP port 9 (discard) is never bound on CI or dev machines — same probe the
                // in-repo NetworkHarnessTestSuite uses for this contract.
                val ran =
                    withNetworkHarness(controllerPort = 9) {
                        executed = true
                    }
                assertFalse(ran, "withNetworkHarness claimed to run against a dead controller port")
                assertFalse(executed, "block executed despite unreachable controller")
                println("[consumer-smoke] skip-when-down OK — withNetworkHarness returned false, block never ran")
            }
        }
}
