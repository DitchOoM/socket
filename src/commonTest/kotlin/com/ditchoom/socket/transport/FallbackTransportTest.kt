package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.ConnectionFailureReason
import com.ditchoom.socket.SSLHandshakeFailedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.TransportsExhausted
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A `Transport` fake that plays a scripted outcome per connect (RFC §10's `ScriptedTransport`), so the
 * whole fallback loop is exercised deterministically with no real network. Successes hand back a live
 * in-memory `ByteStream`; [Hang] suspends forever so a per-attempt timeout can fire.
 */
private class ScriptedTransport(
    private val name: String,
    private vararg val outcomes: Outcome,
) : Transport {
    sealed interface Outcome {
        data object Succeed : Outcome

        data class Fail(
            val error: Throwable,
        ) : Outcome

        data object Hang : Outcome
    }

    private val script = ArrayDeque(outcomes.toList())
    var connectCount = 0
        private set

    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream {
        connectCount++
        return when (val outcome = script.removeFirstOrNull() ?: Outcome.Succeed) {
            Outcome.Succeed -> MemoryTransport.createPair(config).first
            is Outcome.Fail -> throw outcome.error
            Outcome.Hang -> awaitCancellation()
        }
    }

    override fun toString() = name
}

class FallbackTransportTest {
    private val config = TransportConfig()

    @Test
    fun fallsThroughToTheWorkingTransport() =
        runTest {
            val a = ScriptedTransport("A", ScriptedTransport.Outcome.Fail(SocketConnectionException.Refused("h", 1)))
            val b = ScriptedTransport("B", ScriptedTransport.Outcome.Succeed)
            val stream = FallbackTransport(listOf(a, b)).connect("h", 1, config)
            assertTrue(stream.isOpen)
            assertEquals(1, a.connectCount)
            assertEquals(1, b.connectCount)
        }

    @Test
    fun fatalBadCertStopsAndRethrowsWithoutTryingRemainingRungs() =
        runTest {
            val a =
                ScriptedTransport(
                    "A",
                    ScriptedTransport.Outcome.Fail(
                        SSLHandshakeFailedException("bad cert", reason = ConnectionFailureReason.TlsBadCertificate),
                    ),
                )
            val b = ScriptedTransport("B", ScriptedTransport.Outcome.Succeed)
            assertFailsWith<SSLHandshakeFailedException> { FallbackTransport(listOf(a, b)).connect("h", 1, config) }
            assertEquals(0, b.connectCount, "must not try a weaker rung after a fatal cert failure")
        }

    @Test
    fun allRungsFailingThrowsTransportsExhausted() =
        runTest {
            val a = ScriptedTransport("A", ScriptedTransport.Outcome.Fail(SocketTimeoutException("t")))
            val b = ScriptedTransport("B", ScriptedTransport.Outcome.Fail(SocketConnectionException.Refused("h", 1)))
            val e = assertFailsWith<TransportsExhausted> { FallbackTransport(listOf(a, b)).connect("h", 1, config) }
            assertEquals(2, e.failures.size)
        }

    @Test
    fun deterministicPerHostFailureDemotesTheRungOnTheNextConnect() =
        runTest {
            // A refuses (per-host capability) then would succeed; B always succeeds.
            val a =
                ScriptedTransport(
                    "A",
                    ScriptedTransport.Outcome.Fail(SocketConnectionException.Refused("h", 1)),
                    ScriptedTransport.Outcome.Succeed,
                )
            val b = ScriptedTransport("B", ScriptedTransport.Outcome.Succeed, ScriptedTransport.Outcome.Succeed)
            val fallback = FallbackTransport(listOf(a, b), cache = InMemoryCapabilityCache())

            fallback.connect("h", 1, config) // A fails → demoted per-host; B wins
            fallback.connect("h", 1, config) // order now [B, A]; B wins first, A never tried

            assertEquals(1, a.connectCount, "A should be demoted and skipped on the second connect")
            assertEquals(2, b.connectCount)
        }

    @Test
    fun transientTimeoutNeverDemotesTheRung() =
        runTest {
            // The router scenario: A times out transiently, then recovers. It must NOT be demoted, so the
            // next connect still tries the preferred A first (RFC §6 cache-poisoning guard).
            val a =
                ScriptedTransport(
                    "A",
                    ScriptedTransport.Outcome.Fail(SocketTimeoutException("blip")),
                    ScriptedTransport.Outcome.Succeed,
                )
            val b = ScriptedTransport("B", ScriptedTransport.Outcome.Succeed, ScriptedTransport.Outcome.Succeed)
            val fallback = FallbackTransport(listOf(a, b), cache = InMemoryCapabilityCache())

            fallback.connect("h", 1, config) // A transient-fail, B wins; A NOT demoted
            fallback.connect("h", 1, config) // order still [A, B]; A tried first and now succeeds

            assertEquals(2, a.connectCount, "A must stay preferred after a transient-only failure")
            assertEquals(1, b.connectCount)
        }

    @Test
    fun genuineCancellationPropagatesAndIsNotSwallowedIntoFallback() =
        runTest {
            val a = ScriptedTransport("A", ScriptedTransport.Outcome.Fail(CancellationException("cancelled")))
            val b = ScriptedTransport("B", ScriptedTransport.Outcome.Succeed)
            assertFailsWith<CancellationException> { FallbackTransport(listOf(a, b)).connect("h", 1, config) }
            assertEquals(0, b.connectCount, "cancellation must abort the chain, not fall forward")
        }

    @Test
    fun perAttemptTimeoutFallsForwardPastAHungTransport() =
        runTest {
            val a = ScriptedTransport("A", ScriptedTransport.Outcome.Hang) // never returns → attempt times out
            val b = ScriptedTransport("B", ScriptedTransport.Outcome.Succeed)
            val stream = FallbackTransport(listOf(a, b)).connect("h", 1, config.copy(connectTimeout = 5.seconds))
            assertTrue(stream.isOpen)
            assertEquals(1, b.connectCount, "should fall forward past the hung rung")
        }
}
