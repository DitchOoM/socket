package com.ditchoom.socket

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReconnectionClassifierTests {
    // ── Non-recoverable → GiveUp ────────────────────────────────────────

    @Test
    fun sslHandshakeFailure_givesUp() =
        runTest {
            val classifier = DefaultReconnectionClassifier()
            val result = classifier.classify(SSLHandshakeFailedException("cert rejected"))
            assertIs<ReconnectDecision.GiveUp>(result)
        }

    @Test
    fun sslProtocolException_givesUp() =
        runTest {
            val classifier = DefaultReconnectionClassifier()
            val result = classifier.classify(SSLProtocolException("TLS mismatch"))
            assertIs<ReconnectDecision.GiveUp>(result)
        }

    @Test
    fun unknownHost_givesUp() =
        runTest {
            val classifier = DefaultReconnectionClassifier()
            val result = classifier.classify(SocketUnknownHostException("no.such.host"))
            assertIs<ReconnectDecision.GiveUp>(result)
        }

    // ── Recoverable → RetryAfter ────────────────────────────────────────

    @Test
    fun connectionRefused_retries() =
        runTest {
            val classifier = DefaultReconnectionClassifier()
            val result = classifier.classify(SocketConnectionException.Refused("127.0.0.1", 8080))
            assertIs<ReconnectDecision.RetryAfter>(result)
        }

    @Test
    fun connectionReset_retries() =
        runTest {
            val classifier = DefaultReconnectionClassifier()
            val result = classifier.classify(SocketClosedException.ConnectionReset("reset"))
            assertIs<ReconnectDecision.RetryAfter>(result)
        }

    @Test
    fun timeout_retries() =
        runTest {
            val classifier = DefaultReconnectionClassifier()
            val result = classifier.classify(SocketTimeoutException("timed out"))
            assertIs<ReconnectDecision.RetryAfter>(result)
        }

    @Test
    fun genericIO_retries() =
        runTest {
            val classifier = DefaultReconnectionClassifier()
            val result = classifier.classify(SocketIOException("I/O error"))
            assertIs<ReconnectDecision.RetryAfter>(result)
        }

    @Test
    fun unknownRuntimeException_retries() =
        runTest {
            val classifier = DefaultReconnectionClassifier()
            val result = classifier.classify(RuntimeException("something unexpected"))
            assertIs<ReconnectDecision.RetryAfter>(result)
        }

    // ── Backoff progression ─────────────────────────────────────────────

    @Test
    fun backoffProgression_doublesUntilCapped() =
        runTest {
            val classifier =
                DefaultReconnectionClassifier(
                    initialDelay = 100.milliseconds,
                    maxDelay = 15.seconds,
                    factor = 2.0,
                )
            val error = SocketIOException("transient")

            val delays = mutableListOf<kotlin.time.Duration>()
            repeat(10) {
                val result = classifier.classify(error)
                assertIs<ReconnectDecision.RetryAfter>(result)
                delays.add(result.delay)
            }

            // Expected: 100ms, 200ms, 400ms, 800ms, 1.6s, 3.2s, 6.4s, 12.8s, 15s, 15s
            assertTrue(delays[0] == 100.milliseconds, "First delay: ${delays[0]}")
            assertTrue(delays[1] == 200.milliseconds, "Second delay: ${delays[1]}")
            assertTrue(delays[2] == 400.milliseconds, "Third delay: ${delays[2]}")
            assertTrue(delays[3] == 800.milliseconds, "Fourth delay: ${delays[3]}")
            // Last two should be capped at maxDelay
            assertTrue(delays[8] == 15.seconds, "Ninth delay should be capped: ${delays[8]}")
            assertTrue(delays[9] == 15.seconds, "Tenth delay should be capped: ${delays[9]}")
        }

    // ── Reset ───────────────────────────────────────────────────────────

    @Test
    fun reset_restoresInitialDelay() =
        runTest {
            val classifier =
                DefaultReconnectionClassifier(
                    initialDelay = 100.milliseconds,
                    maxDelay = 15.seconds,
                )
            val error = SocketIOException("transient")

            // Advance backoff a few times
            repeat(5) { classifier.classify(error) }

            // Reset and verify we're back to initial delay
            classifier.reset()
            val result = classifier.classify(error)
            assertIs<ReconnectDecision.RetryAfter>(result)
            assertTrue(result.delay == 100.milliseconds, "After reset: ${result.delay}")
        }

    // ── SAM classifier ──────────────────────────────────────────────────

    @Test
    fun samClassifier_withSuspend() =
        runTest {
            val classifier =
                ReconnectionClassifier { error ->
                    // Simulate a suspend call (e.g., waiting for network)
                    kotlinx.coroutines.delay(1)
                    if (error is SocketUnknownHostException) {
                        ReconnectDecision.GiveUp
                    } else {
                        ReconnectDecision.RetryAfter(1.seconds)
                    }
                }

            assertIs<ReconnectDecision.GiveUp>(
                classifier.classify(SocketUnknownHostException("bad.host")),
            )
            assertIs<ReconnectDecision.RetryAfter>(
                classifier.classify(SocketIOException("transient")),
            )
        }

    // ── Companion utility ───────────────────────────────────────────────

    @Test
    fun isNonRecoverable_staticUtility() {
        assertTrue(DefaultReconnectionClassifier.isNonRecoverable(SSLHandshakeFailedException("x")))
        assertTrue(DefaultReconnectionClassifier.isNonRecoverable(SSLProtocolException("x")))
        assertTrue(DefaultReconnectionClassifier.isNonRecoverable(SocketUnknownHostException("x")))
        assertTrue(!DefaultReconnectionClassifier.isNonRecoverable(SocketIOException("x")))
        assertTrue(!DefaultReconnectionClassifier.isNonRecoverable(RuntimeException("x")))
    }
}
