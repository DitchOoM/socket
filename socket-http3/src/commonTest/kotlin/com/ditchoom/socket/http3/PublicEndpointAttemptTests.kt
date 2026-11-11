package com.ditchoom.socket.http3

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
}
