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
 * subclasses override the engine block-takers and inherit every test below.
 * Internal-state assertions (scope cancellation, child coroutine counts) stay in
 * the JVM-only subclass because they need reflection; everything here is
 * externally observable — close returns, handlers terminate, cycles don't hang.
 *
 * **Lifecycle:** see [QuicServerTestSuite] — same scope-only construction
 * via [withServerEngine] / [withClientEngine]. Test bodies that explicitly
 * call `engine.close()` (e.g. `engineCloseCancelsEngineScope` in the JVM
 * subclass) remain correct because `close()` is idempotent.
 */
abstract class QuicServerLifecycleTestSuite {
    abstract suspend fun <R> withServerEngine(block: suspend (QuicServerEngine) -> R): R

    abstract suspend fun <R> withClientEngine(block: suspend (QuicEngine) -> R): R

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
            withServerEngine { engine ->
                val server = engine.bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
                withTimeout(2.seconds) { server.close() }
            }
        }

    @Test
    fun closeWhileConnectionsBlockingDoesNotDeadlock() =
        runQuicTest {
            withServerEngine { engine ->
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
        }

    @Test
    fun multipleBindCloseCyclesCompletePromptly() =
        runQuicTest {
            withServerEngine { engine ->
                // Each cycle individually bounded; if any one hangs, the per-cycle
                // timeout fails with a clear signal rather than exhausting the
                // whole suite's wall clock.
                repeat(10) {
                    val server = engine.bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
                    withTimeout(2.seconds) { server.close() }
                }
            }
        }
}
