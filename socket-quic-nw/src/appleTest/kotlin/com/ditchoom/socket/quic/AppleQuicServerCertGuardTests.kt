@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for the Network.framework QUIC **server** anti-amplification guard
 * ([guardAppleServerCertFlight]). Apple's libquic under-credits a non-Apple client's first flight for
 * RFC 9000 §8.1, so an oversized (RSA-2048) server certificate flight can never be delivered and the
 * handshake silently deadlocks against quiche/Chrome. The guard estimates the leaf's TLS flight at
 * bind time (via `nw_helper_quic_identity_leaf_info`) and throws a clear error for an oversized leaf,
 * turning a 10s timeout into an instant, actionable failure. A small EC P-256 leaf passes, and the
 * Apple-only [QuicOptions.appleAllowOversizedServerCert] escape hatch bypasses it.
 *
 * The bind is synchronous up to the guard, so these never touch the network — but they still read the
 * `testcerts/` leaf fixtures, absent from a `--standalone` simulator's cwd, so each skips there.
 */
class AppleQuicServerCertGuardTests {
    private val opts =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    /** An RSA-2048 NW server leaf overflows NW's budget → bind fails loud (not a silent deadlock). */
    @Test
    fun rsaServerCertRejectedByGuard(): TestResult =
        runTest {
            // The guard never touches the network, but it still reads the leaf from the `testcerts/`
            // fixtures, which aren't on a `--standalone` simulator's cwd (see
            // [shouldSkipQuicHarnessOnSimulator]).
            if (shouldSkipQuicHarnessOnSimulator()) return@runTest
            val ex =
                try {
                    withQuicServer(
                        port = 0,
                        tlsConfig = appleQuicPinnedTlsConfig("pinned-rsa"),
                        quicOptions = opts,
                        timeout = 5.seconds,
                    ) {}
                    fail("Expected the anti-amplification guard to reject the RSA NW server cert")
                } catch (e: IllegalArgumentException) {
                    e
                }
            val message = ex.message ?: ""
            assertTrue("RSA" in message, "guard error should name the RSA key type; got: $message")
            assertTrue(
                "appleAllowOversizedServerCert" in message,
                "guard error should name the opt-out flag; got: $message",
            )
            assertTrue(
                "anti-amplification" in message,
                "guard error should name the NW anti-amplification bug; got: $message",
            )
        }

    /** A small EC P-256 NW server leaf fits NW's budget → bind succeeds with the guard ON (default). */
    @Test
    fun ecServerCertPassesGuard(): TestResult =
        runTest(timeout = 40.seconds) {
            if (shouldSkipQuicHarnessOnSimulator()) return@runTest
            // Real dispatcher: bind() resolves on Network.framework's serial callback queue, which
            // runTest's virtual clock never advances.
            withContext(Dispatchers.Default) {
                var bound = false
                withQuicServer(
                    port = 0,
                    tlsConfig = appleQuicTestTlsConfig(),
                    quicOptions = opts,
                    timeout = 10.seconds,
                ) {
                    // Reaching the block means bind() ran past the guard and the listener is up.
                    bound = port > 0
                }
                assertTrue(bound, "EC P-256 server cert should bind cleanly under the anti-amplification guard")
            }
        }

    /** The opt-out lets an Apple-clients-only deployment keep an RSA leaf despite the guard. */
    @Test
    fun rsaServerCertAllowedWhenOptedOut(): TestResult =
        runTest(timeout = 40.seconds) {
            if (shouldSkipQuicHarnessOnSimulator()) return@runTest
            withContext(Dispatchers.Default) {
                var bound = false
                withQuicServer(
                    port = 0,
                    tlsConfig = appleQuicPinnedTlsConfig("pinned-rsa"),
                    quicOptions = opts.copy(appleAllowOversizedServerCert = true),
                    timeout = 10.seconds,
                ) {
                    bound = port > 0
                }
                assertTrue(bound, "appleAllowOversizedServerCert=true should bypass the guard and bind the RSA cert")
            }
        }
}
