package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.testkit.fault.FaultSchedule
import com.ditchoom.socket.testsuite.harness.NetworkHarnessScope
import com.ditchoom.socket.testsuite.harness.withNetworkHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * **Our client against our server across an impaired path** — the first interop cell in this repository
 * that is neither an in-process pipe nor a clean loopback.
 *
 * `udp-toxi` is transport-agnostic: it moves datagrams, and QUIC is datagrams, so pointing a second
 * named relay at `quic-echo` puts a real handshake and a real stream behind real packet loss (P2
 * `impairedQuic` of RFC_UNIFIED_NETWORK_TEST_HARNESS).
 *
 * ## What these assert, and what they deliberately do not
 * A dropped datagram here is a **lost QUIC packet**, so loss is absorbed by loss recovery and surfaces
 * as latency, not as a failed read. Asserting "the echo came back" is the honest assertion; asserting
 * anything per-datagram would be asserting quiche's recovery schedule, which is not ours and changes
 * between releases.
 *
 * **But a successful echo does not prove anything was dropped**, so every impaired case also asserts
 * the relay's own tally ([NetworkHarnessScope.quicRelayStats]). Without it this suite passes
 * identically against a relay that silently forwarded everything — the shape that makes an impairment
 * matrix green and meaningless, and the one the UDP suite avoids by asserting a specific datagram.
 *
 * ⚠️ **`dropEvery(n)` defaults to `offset = 0`, so the first datagram of each impaired leg is dropped —
 * which is the client's first Initial.** The costs are therefore handshake recovery, not steady-state
 * loss: measured 1.1s for the uplink case (one Initial PTO at quiche's 333ms initial RTT) and 18.2s for
 * both legs (PTO exponential backoff, 1+2+4+8…). That distribution is discrete in powers of two, so
 * "margin" is not continuous here — one more unlucky alignment is +16s.
 *
 * ## Why JVM-only
 * Same reasoning as `UdpHarnessTests`: the sidecars are reachable from `jvmTest` under the `harnessUp`
 * window, and this needs `:socket-testsuite`'s control plane.
 *
 * **Skip-on-unreachable, never flaky-fail:** every body runs inside [withNetworkHarness], so with the
 * stack down each test is a printed skip rather than a failure. Assertions inside the block are real.
 *
 * ⚠️ These can only fail as of #577. Before it, a wrong answer from a reachable harness was swallowed
 * and reported as a skip.
 */
class QuicImpairedHarnessTests {
    private val bufferFactory = BufferFactory.network()

    /**
     * ⚠️ **The budget that matters is [withQuicConnection]'s, not the idle timeout.** RFC 9000 §10.1
     * makes the effective idle timeout the *minimum* of the two endpoints', and `QuicEchoTestServer`
     * advertises 30s — so raising the client's above that changes nothing, and idle never fires here
     * anyway because PTO probes keep packets arriving. Stated because an earlier revision credited this
     * knob for headroom that `withQuicConnection(timeout = …)` and the per-read deadlines provide.
     */
    private val quicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
        )

    /** One stream round trip — the assertion these tests are actually about. */
    private suspend fun QuicScope.echoOnce(payload: String): String {
        val stream = openStream()
        val out = bufferFactory.allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        stream.write(out, 60.seconds)
        out.freeNativeMemory()
        val read = stream.read(60.seconds)
        // assertIs, not a NO_DATA sentinel: ReadResult is sealed, and collapsing End/Closed/Reset into
        // one string would report "expected X but was NO_DATA" and lose which of them it was — on the
        // failures where the variant is the whole diagnosis.
        assertIs<ReadResult.Data>(read, "the impaired stream produced $read instead of data")
        val text = read.buffer.readString(read.buffer.remaining(), Charset.UTF8)
        read.buffer.freeIfNeeded()
        stream.close()
        return text
    }

    /** Run one echo through the relay at [endpointHost]:[endpointPort] and return what came back. */
    private suspend fun echoThroughRelay(
        endpointHost: String,
        endpointPort: Int,
        payload: String,
    ): String {
        var echoed = ""
        withQuicConnection(endpointHost, endpointPort, quicOptions, timeout = 60.seconds) {
            echoed = echoOnce(payload)
        }
        return echoed
    }

    @Test
    fun aCleanRelayRoundTripsAStream() {
        runBlocking(Dispatchers.IO) {
            withNetworkHarness {
                // The control: the relay itself must not break QUIC. Without it a red impaired case
                // could be the relay rather than the loss.
                impairedQuic(clientToServer = FaultSchedule.CLEAN) { endpoint ->
                    assertEquals(
                        "through-the-relay",
                        echoThroughRelay(endpoint.host, endpoint.port, "through-the-relay"),
                        "a CLEAN udp-toxi relay broke a QUIC stream, so every impaired assertion beside " +
                            "this one would be measuring the relay rather than the impairment",
                    )
                    val stats = quicRelayStats()
                    assertEquals(
                        0,
                        stats.clientToServer.dropped,
                        "the CLEAN control dropped datagrams ($stats), so a schedule leaked across tests " +
                            "and every 'survives loss' result below is against an unknown impairment",
                    )
                }
            }
        }
    }

    @Test
    fun aStreamSurvivesLossOnTheClientToServerLeg() {
        runBlocking(Dispatchers.IO) {
            withNetworkHarness {
                impairedQuic(clientToServer = FaultSchedule { dropEvery(n = 3) }) { endpoint ->
                    assertEquals(
                        "lossy-uplink",
                        echoThroughRelay(endpoint.host, endpoint.port, "lossy-uplink"),
                        "a stream did not survive one-in-three loss on the uplink — QUIC absorbs that " +
                            "through loss recovery, so this is retransmission failing, not a lossy network",
                    )
                    val stats = quicRelayStats()
                    assertTrue(
                        stats.clientToServer.dropped > 0,
                        "the uplink schedule was accepted but nothing was dropped ($stats) — the echo " +
                            "succeeded because the path was clean, so this proved nothing",
                    )
                }
            }
        }
    }

    @Test
    fun aStreamSurvivesLossOnBothLegs() {
        runBlocking(Dispatchers.IO) {
            withNetworkHarness {
                impairedQuic(
                    clientToServer = FaultSchedule { dropEvery(n = 4) },
                    serverToClient = FaultSchedule { dropEvery(n = 4) },
                ) { endpoint ->
                    assertEquals(
                        "lossy-both-ways",
                        echoThroughRelay(endpoint.host, endpoint.port, "lossy-both-ways"),
                        "loss on both legs killed the exchange — impairing both exercises recovery the " +
                            "uplink-only case does not",
                    )
                    val stats = quicRelayStats()
                    assertTrue(
                        stats.clientToServer.dropped > 0 && stats.serverToClient.dropped > 0,
                        "one or both legs dropped nothing ($stats) — a two-leg assertion that only " +
                            "impaired one leg is a one-leg test wearing the wrong name",
                    )
                }
            }
        }
    }
}
