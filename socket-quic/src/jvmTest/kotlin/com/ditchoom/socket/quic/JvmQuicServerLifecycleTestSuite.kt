package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * JVM subclass of [QuicServerLifecycleTestSuite].
 *
 * Inherits every black-box invariant from the base suite, then layers on
 * one internal-state assertion (serverJob cancellation) that requires
 * reflection.
 *
 * **What's gone:** four reflection tests that asserted on the *engine
 * scope*'s child-count after operations. The engine layer was removed in
 * the no-engine refactor (see `socket-quic/DRIVER_REDESIGN.md` → "Engine
 * lifecycle"); the per-call parent scope is now an implementation detail
 * of [withQuicServer] and is cancelled by construction on every exit
 * path. The black-box invariants in the base suite —
 * `closeWhileConnectionsBlockingDoesNotDeadlock` and
 * `multipleBindCloseCyclesCompletePromptly` — verify the externally
 * observable consequences of the same property.
 */
class JvmQuicServerLifecycleTestSuite : QuicServerLifecycleTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() =
        QuicTlsConfig(
            certChainPath = certPath("cert.crt"),
            privKeyPath = certPath("cert.key"),
        )

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    // ── Reflection helper — test-only access to server internals ──

    private fun serverChildJob(server: QuicServer): Job {
        val jobField = server::class.java.getDeclaredField("serverJob").apply { isAccessible = true }
        return jobField.get(server) as Job
    }

    // ── JVM-only invariant (reflection-backed) ─────────────────────

    @Test
    fun serverCloseCancelsItsChildScope() =
        runBlocking(Dispatchers.IO) {
            wrapTestBody {
                lateinit var captured: Job
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                    captured = serverChildJob(this@withQuicServer)
                    assertTrue(captured.isActive, "serverJob should be active while bound")
                }
                // After the withQuicServer block exits, server.close() has run inside
                // the helper. serverJob must be cancelled.
                assertTrue(captured.isCancelled, "serverJob should be cancelled after withQuicServer exit")
            }
        }
}
