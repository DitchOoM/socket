package com.ditchoom.socket.quic

import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * Platform-neutral lifecycle invariants for [QuicServerEngine] / [QuicServer].
 *
 * Mirrors the [QuicServerTestSuite] / [JvmQuicServerTestSuite] split — platform
 * subclasses override the three abstract factories and inherit every test below.
 * Internal-state assertions (scope cancellation, child coroutine counts) stay in
 * the JVM-only subclass because they need reflection; everything here is
 * externally observable — close returns, handlers terminate, cycles don't hang.
 */
abstract class QuicServerLifecycleTestSuite {
    abstract fun serverEngine(): QuicServerEngine

    abstract fun clientEngine(): QuicEngine

    abstract fun testTlsConfig(): QuicTlsConfig

    protected val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    @Test
    fun serverCloseReturnsWithinTwoSeconds() =
        runQuicTest {
            val server = serverEngine().bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
            withTimeout(2.seconds) { server.close() }
        }

    @Test
    fun closeWhileConnectionsBlockingDoesNotDeadlock() =
        runQuicTest {
            val engine = serverEngine()
            val server = engine.bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)

            // connections() suspends on an empty channel forever. Must not
            // prevent close() from returning — regardless of whether the
            // handler coroutine has been scheduled yet.
            val handlerJob =
                launch {
                    server.connections {
                        // Unreachable — no client ever arrives in this test.
                    }
                }

            withTimeout(2.seconds) { server.close() }

            // Handler should unblock via the server's scope cancellation.
            val result = withTimeoutOrNull(2.seconds) { handlerJob.join() }
            assertNotNull(result, "connections() handler did not terminate after server.close()")
        }

    @Test
    fun multipleBindCloseCyclesCompletePromptly() =
        runQuicTest {
            val engine = serverEngine()

            // Each cycle individually bounded; if any one hangs, the per-cycle
            // timeout fails with a clear signal rather than exhausting the
            // whole suite's wall clock.
            repeat(10) {
                val server = engine.bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
                withTimeout(2.seconds) { server.close() }
            }
        }
}
