@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.transport.sim

import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.ReconnectionClassifier
import com.ditchoom.socket.SocketIOException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.CodecConnection
import com.ditchoom.socket.transport.MemoryTransport
import com.ditchoom.socket.transport.MockNetworkMonitor
import com.ditchoom.socket.transport.ReconnectingConnection
import com.ditchoom.socket.transport.TestStringCodec
import com.ditchoom.socket.transport.sim.fixtures.networkFlapReconnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The `network-flap-reconnect` golden (W2 fixture 3) at the [ReconnectingConnection] tier — no
 * quiche anywhere. The timeline engine injects the `networkId` flap; the trace oracle asserts the
 * backoff-cut trajectory and the 50× determinism loop pins it run-over-run.
 */
class NetworkFlapReconnectSimTests {
    private val testOptions =
        TransportConfig(
            readPolicy = ReadPolicy.Bounded(5.seconds),
            writePolicy = WritePolicy.Bounded(5.seconds),
        )

    /**
     * One full fixture run: first connect fails (60s backoff would hold to t=60s), the t=1s flap
     * must cut it short, the second connect delivers "recovered" and ends cleanly.
     */
    private suspend fun TestScope.runNetworkFlapReconnect(): SimTrace {
        val t0 = testScheduler.currentTime
        val trace = SimTrace { (testScheduler.currentTime - t0).milliseconds }
        // UNAVAILABLE throughout: the availability->resetBackoff path never fires; only the
        // networkId change can cut the 60s backoff short (same setup as the classic test).
        val monitor = MockNetworkMonitor(NetworkAvailability.UNAVAILABLE)
        var connectCount = 0
        val conn =
            ReconnectingConnection(
                connect = {
                    connectCount++
                    trace.record(Observed.ConnectAttempt(trace.now(), connectCount))
                    if (connectCount == 1) {
                        throw SocketIOException("transient network flap")
                    }
                    val (client, server) = MemoryTransport.createPair()
                    val codec = CodecConnection(stream = client, codec = TestStringCodec, config = testOptions)
                    val serverCodec = CodecConnection(stream = server, codec = TestStringCodec, config = testOptions)
                    serverCodec.send("recovered")
                    serverCodec.close()
                    codec
                },
                classifier = ReconnectionClassifier { ReconnectDecision.RetryAfter(60.seconds) },
                monitorFactory = { monitor },
            )

        val simScope = CoroutineScope(coroutineContext + Job())
        simScope.launch {
            conn.state.collect { trace.record(Observed.StateChange(trace.now(), describeConnectionState(it))) }
        }
        simScope.launch {
            monitor.networkId.drop(1).collect { trace.record(Observed.NetworkChanged(trace.now(), it)) }
        }
        testScheduler.runCurrent() // collectors subscribed before the connection starts

        val received = mutableListOf<String>()
        val receiveJob = simScope.launch { conn.receive().collect { received += it } }
        testScheduler.runCurrent() // first attempt fails at t0 and parks in the 60s backoff race

        with(SimTimeline(networkFlapReconnect)) { run(SimHarness(monitor, SimLiveness(trace), trace)) }

        receiveJob.join()
        simScope.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("recovered"), received, "the post-flap reconnect must deliver the stream")
        assertEquals(2, connectCount, "exactly one failed attempt and one flap-triggered retry")
        return trace
    }

    @Test
    fun networkFlapReconnect_flapCutsBackoffShort() =
        runTest {
            val trace = runNetworkFlapReconnect()
            // The wait was interrupted at the flap — virtual time never reached the 60s backoff.
            assertTrue(
                testScheduler.currentTime < 60.seconds.inWholeMilliseconds,
                "backoff should have been cut short by the network change, elapsed=${testScheduler.currentTime}ms",
            )
            trace.assertSequence {
                at(Duration.ZERO, "first connect attempt") { it is Observed.ConnectAttempt && it.attempt == 1 }
                at(1.seconds, "flap-triggered reconnect races the 60s backoff") {
                    it is Observed.ConnectAttempt && it.attempt == 2
                }
                anyTime("stream ends cleanly after recovery") {
                    it is Observed.StateChange && it.state == "Disconnected(clean)"
                }
            }
            // Recorded per RFC §5 item 2 — the flap itself is visible in the trace at its instant.
            assertTrue(
                trace.events.any { it is Observed.NetworkChanged && it.at == 1.seconds },
                "the networkId flap must be recorded at t=1s\n${trace.render()}",
            )
        }

    @Test
    fun networkFlapReconnect_deterministic50x() =
        runTest {
            var golden: List<Observed>? = null
            repeat(50) {
                val trace = runNetworkFlapReconnect()
                val expected = golden
                if (expected == null) {
                    golden = trace.events.toList()
                } else {
                    trace.assertMatches(expected)
                }
            }
        }
}
