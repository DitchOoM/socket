package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.testsuite.harness.withNetworkHarness
import com.ditchoom.socket.testkit.fault.FaultSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * **Our client against our server across an impaired path** — the first interop cell in this repository
 * that is neither an in-process pipe nor a clean loopback.
 *
 * `udp-toxi` is transport-agnostic: it moves datagrams, and QUIC is datagrams, so pointing a second
 * named relay at `quic-echo` is all it takes to put a real handshake and a real stream behind real
 * packet loss (P2 `impairedQuic` of RFC_UNIFIED_NETWORK_TEST_HARNESS).
 *
 * ## What these assert, and what they deliberately do not
 * A dropped datagram here is a **lost QUIC packet**, so loss is absorbed by loss recovery and surfaces
 * as latency, not as a failed read. Asserting "the echo came back" is therefore the honest assertion;
 * asserting anything per-datagram would be asserting quiche's recovery schedule, which is not ours and
 * changes between releases.
 *
 * The interesting failure this can catch is the opposite one: loss that the connection does *not*
 * absorb — a handshake that never completes, a stream that reports end instead of data, a connection
 * that dies rather than retransmits. Those are ours.
 *
 * ## Why JVM-only
 * Same reasoning as `UdpHarnessTests`: the harness sidecars are reachable from `jvmTest` under the
 * `harnessUp` window on Linux CI and dev machines, and this needs `:socket-testsuite`'s control plane.
 *
 * **Skip-on-unreachable, never flaky-fail:** every body runs inside [withNetworkHarness], so with the
 * stack down each test is a printed skip rather than a failure. Assertions inside the block are real.
 *
 * ⚠️ These tests can only fail as of #577. Before that a wrong answer from a reachable harness was
 * swallowed and reported as a skip, which is exactly the shape that would make an impairment matrix
 * green and meaningless.
 */
class QuicImpairedHarnessTests {
    private val bufferFactory = BufferFactory.network()

    private val quicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
        )

    /** One stream round trip, as the assertion these tests are actually about. */
    private suspend fun QuicScope.echo(payload: String): String {
        val stream = openStream()
        val out = bufferFactory.allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        stream.write(out, 30.seconds)
        out.freeNativeMemory()
        val read = stream.read(30.seconds)
        val text =
            if (read is ReadResult.Data) {
                read.buffer.readString(read.buffer.remaining(), Charset.UTF8).also { read.buffer.freeIfNeeded() }
            } else {
                NO_DATA
            }
        stream.close()
        return text
    }

    @Test
    fun aCleanRelayRoundTripsAStream() {
        runBlocking(Dispatchers.IO) {
            withNetworkHarness {
                // The control: the relay itself must not break QUIC. Without this, a red impaired test
                // proves nothing — it could be the relay rather than the loss.
                impairedQuic(clientToServer = FaultSchedule.CLEAN) { endpoint ->
                    withQuicConnection(endpoint.host, endpoint.port, quicOptions, timeout = 30.seconds) {
                        assertEquals(
                            "through-the-relay",
                            echo("through-the-relay"),
                            "a CLEAN udp-toxi relay broke a QUIC stream, so every impaired assertion " +
                                "beside this one would be measuring the relay rather than the impairment",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun aStreamSurvivesLossOnTheClientToServerLeg() {
        runBlocking(Dispatchers.IO) {
            withNetworkHarness {
                impairedQuic(clientToServer = FaultSchedule { dropEvery(n = 3) }) { endpoint ->
                    withQuicConnection(endpoint.host, endpoint.port, quicOptions, timeout = 30.seconds) {
                        assertEquals(
                            "lossy-uplink",
                            echo("lossy-uplink"),
                            "a stream did not survive one-in-three loss on the uplink. QUIC is supposed to " +
                                "absorb that through loss recovery, so this is retransmission failing, not " +
                                "the network being lossy",
                        )
                    }
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
                    withQuicConnection(endpoint.host, endpoint.port, quicOptions, timeout = 30.seconds) {
                        assertEquals(
                            "lossy-both-ways",
                            echo("lossy-both-ways"),
                            "loss on both legs killed the exchange. Symmetric loss also delays ACKs, so this " +
                                "exercises the recovery path the uplink-only case does not",
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val NO_DATA = "NO_DATA"
    }
}
