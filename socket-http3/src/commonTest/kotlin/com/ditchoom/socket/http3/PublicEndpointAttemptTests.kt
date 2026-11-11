package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.PeerCertificateChain
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.ServerCertVerifier
import com.ditchoom.socket.testkit.trace.TraceEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The public-endpoint tests' verdicts, on attempts whose behaviour is known, without the network. */
class PublicEndpointAttemptTests {
    @Test
    fun aFinishedExchangeIsDoneWithItsValue() =
        runTest {
            withContext(Dispatchers.Default) {
                val outcome =
                    PublicEndpointAttempt.run("e", 5.seconds) {
                        enter("request")
                        200
                    }
                assertIs<PublicEndpointAttempt.Outcome.Done<Int>>(outcome)
                assertEquals(200, outcome.value)
            }
        }

    @Test
    fun aFailedPhaseIsUnreachableAndNamesThePhase() =
        runTest {
            withContext(Dispatchers.Default) {
                val outcome =
                    PublicEndpointAttempt.run("e", 5.seconds) {
                        enter("handshake")
                        throw IllegalStateException("no route")
                    }
                assertIs<PublicEndpointAttempt.Outcome.Unreachable>(outcome)
                assertTrue("phase 'handshake'" in outcome.report && "no route" in outcome.report, outcome.report)
            }
        }

    @Test
    fun aStallThatHonoursCancellationIsUnreachableAndNamesThePhase() =
        runTest {
            withContext(Dispatchers.Default) {
                val outcome =
                    PublicEndpointAttempt.run("e", 100.milliseconds, cancelGrace = 5.seconds) {
                        enter("response body")
                        awaitCancellation()
                    }
                assertIs<PublicEndpointAttempt.Outcome.Unreachable>(outcome)
                assertTrue("stalled in phase 'response body'" in outcome.report, outcome.report)
            }
        }

    @Test
    fun aStallThatIgnoresCancellationIsCalledOut() =
        runTest {
            withContext(Dispatchers.Default) {
                val outcome =
                    PublicEndpointAttempt.run("e", 100.milliseconds, cancelGrace = 200.milliseconds) {
                        enter("dns")
                        // A wait cancellation cannot reach, like a lookup blocked in the system resolver.
                        withContext(NonCancellable) { delay(2.seconds) }
                    }
                assertIs<PublicEndpointAttempt.Outcome.IgnoredCancellation>(outcome)
                assertTrue("stalled in phase 'dns'" in outcome.report, outcome.report)
            }
        }

    /**
     * The one `handshake` phase is split by what the library reports: nothing recorded, datagrams that
     * went unanswered, datagrams that were answered, QUIC established, the chain accepted. Each is what a
     * stalled attempt's report must name, so the next stall says which wait it was.
     */
    @Test
    fun aStalledHandshakeNamesTheWaitItWasIn() =
        runTest {
            withContext(Dispatchers.Default) {
                suspend fun stalledAfter(events: List<TraceEvent>): String {
                    val outcome =
                        PublicEndpointAttempt.run("e", 200.milliseconds, cancelGrace = 5.seconds) {
                            enter("handshake")
                            val sink = observing(QuicOptions(alpnProtocols = listOf("h3"))).trace!!.captureFor().sink
                            events.forEach { sink.emit(it) }
                            awaitCancellation()
                        }
                    return assertIs<PublicEndpointAttempt.Outcome.Unreachable>(outcome).report
                }
                val out = TraceEvent.DgramOut(1.milliseconds, 1200, null, "")
                val inbound = TraceEvent.DgramIn(2.milliseconds, 1200, null, "")
                val established = TraceEvent.State(3.milliseconds, "com.ditchoom.socket.quic.QuicConnectionState.Established", null)

                assertTrue("no datagram recorded" in stalledAfter(emptyList()))
                assertTrue("2 datagram(s) sent and none received" in stalledAfter(listOf(out, out)))
                assertTrue("1 datagram(s) sent and 1 received" in stalledAfter(listOf(out, inbound)))
                assertTrue("platform trust evaluation" in stalledAfter(listOf(out, inbound, established)))
            }
        }

    @Test
    fun aConsultedVerifierMeansTheChainWasAccepted() =
        runTest {
            withContext(Dispatchers.Default) {
                val outcome =
                    PublicEndpointAttempt.run("e", 200.milliseconds, cancelGrace = 5.seconds) {
                        enter("handshake")
                        val verifier: ServerCertVerifier = observing(QuicOptions(alpnProtocols = listOf("h3"))).serverCertVerifiers.single()
                        verifier.verify(PeerCertificateChain("e", emptyList(), kotlin.time.Instant.fromEpochSeconds(0)))
                        awaitCancellation()
                    }
                val report = assertIs<PublicEndpointAttempt.Outcome.Unreachable>(outcome).report
                assertTrue("HTTP/3 bootstrap" in report, report)
            }
        }
}
