package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end integration tests against real QUIC servers.
 *
 * Tests gracefully skip on platforms where the native lib isn't available.
 * Uses scope-based connect — if the block runs, the connection is established.
 */
class QuicIntegrationTests {
    private val quicOptions = QuicOptions(alpnProtocols = listOf("h3"))

    @Test
    fun connectionTimeout_onUnreachableHost() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                val engine =
                    try {
                        defaultQuicEngine()
                    } catch (_: Throwable) {
                        return@withContext
                    }
                try {
                    engine.connect("192.0.2.1", 443, quicOptions, timeout = 2.seconds) {
                        assertTrue(false, "Expected timeout or connection error")
                    }
                } catch (_: Exception) {
                    // Expected: timeout
                }
                engine.close()
            }
        }
}
