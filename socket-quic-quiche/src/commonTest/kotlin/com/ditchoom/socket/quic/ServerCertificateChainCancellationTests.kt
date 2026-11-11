@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * A connect that is cancelled while a platform trust store is still evaluating the server's chain must
 * return, whatever the trust store is doing.
 *
 * Seen on the iOS simulator lane: `Http3PublicEndpointInteropTests` and `WebTransportPublicEndpointInteropTests`
 * both stalled in their handshake phase past their budget and were still there 10 s after being cancelled.
 * iOS validates the chain after the handshake with `SecTrustEvaluateWithError`, a call that blocks its
 * thread while Security.framework fetches revocation data or missing intermediates, and cannot be
 * interrupted. It was awaited from inside the connect, so the connect (and the caller's `withTimeout`,
 * which only resumes once its block has returned) waited for it.
 *
 * The evaluator here blocks its thread the way that call does and is released by the test only after
 * the verdict, so the connect can only return by not waiting for it.
 */
class ServerCertificateChainCancellationTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** Holds a thread until [release]d, the way a blocking platform call does; bounded so a failure cannot hang the run. */
    private class BlockedPlatformCall {
        @Volatile
        private var released = false

        @Volatile
        var entered = false
            private set

        fun block() {
            entered = true
            val start = TimeSource.Monotonic.markNow()
            while (!released && start.elapsedNow() < 30.seconds) {
                // Busy wait: common code has no thread sleep, and the point is a thread that is not suspended.
            }
        }

        fun release() {
            released = true
        }
    }

    private suspend fun verifyWith(trust: PlatformChainTrust) {
        verifyServerCertificateChain(
            serverName = "blocked.example",
            resumption = QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered),
            platformTrust = trust,
            verifiers = emptyList(),
            bufferFactory = bufferFactory,
            readChainDer = { index, der, _ ->
                if (index == 0) {
                    der.writeByte(1)
                    1
                } else {
                    0
                }
            },
            closeConnection = {},
            now = Instant.fromEpochSeconds(0),
        )
    }

    @Test
    fun aCancelledConnectIsNotHeldByATrustStoreThatBlocks() =
        runTest(timeout = 60.seconds) {
            // Real time: runTest's virtual clock would fire every timeout below at once.
            withContext(Dispatchers.Default) {
                val call = BlockedPlatformCall()
                val trust =
                    PlatformChainTrust.AfterHandshake { _, _ ->
                        call.block()
                        SystemTrustVerdict.Trusted
                    }
                val scope = CoroutineScope(Dispatchers.Default + Job() + CoroutineName("connect"))
                try {
                    val connect = scope.async { verifyWith(trust) }
                    withTimeoutOrNull(10.seconds) { while (!call.entered) kotlinx.coroutines.delay(5.milliseconds) }
                    assertTrue(call.entered, "the trust store was never consulted, so this proves nothing")
                    connect.cancel()
                    val returned = withTimeoutOrNull(3.seconds) { connect.join() } != null
                    assertEquals(
                        true,
                        returned,
                        "a connect cancelled while the platform trust store blocks must return; it was still waiting 3 s later",
                    )
                } finally {
                    call.release()
                    scope.coroutineContext[Job]?.cancel()
                }
            }
        }
}
