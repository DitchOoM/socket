package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle invariants for [JvmQuicServer] and [JvmQuicServerEngine].
 *
 * Purpose: pin down the rules that tests downstream of this one (like
 * [StaleConnectionDiagnosticTests]) silently depend on. If these tests fail,
 * we have an isolated reproducer of the underlying lifecycle bug — instead
 * of a flaky integration-level timeout that depends on cumulative
 * test-suite state.
 */
class JvmQuicServerLifecycleTests {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))
    private val opts =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    /** Reach into the engine's internal scope via reflection — test-only access. */
    private fun engineScopeJob(engine: Any): Job {
        val scopeField = engine::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val scope = scopeField.get(engine) as kotlinx.coroutines.CoroutineScope
        return scope.coroutineContext[Job]!!
    }

    private fun serverChildJob(server: QuicServer): Job {
        val jobField = server::class.java.getDeclaredField("serverJob").apply { isAccessible = true }
        return jobField.get(server) as Job
    }

    // ── Server lifecycle ─────────────────────────────────────────────────────

    @Test
    fun serverCloseReturnsWithinTwoSeconds() =
        runBlocking(Dispatchers.IO) {
            val engine = defaultQuicServerEngine()
            val server = engine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = opts)
            withTimeout(2.seconds) { server.close() }
        }

    @Test
    fun serverCloseCancelsItsChildScope() =
        runBlocking(Dispatchers.IO) {
            val engine = defaultQuicServerEngine()
            val server = engine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = opts)
            val childJob = serverChildJob(server)
            assertTrue(childJob.isActive, "serverJob should be active while bound")

            server.close()

            assertTrue(childJob.isCancelled, "serverJob should be cancelled after close()")
        }

    @Test
    fun serverCloseLeavesNoActiveChildrenOnEngineScope() =
        runBlocking(Dispatchers.IO) {
            val engine = defaultQuicServerEngine()
            val beforeChildren = engineScopeJob(engine).children.count()
            val server = engine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = opts)
            server.close()

            // Give the receive loop a moment to unwind after channel.close() fires.
            // Anything still active here is a leak — document the signal explicitly
            // so the test fails loudly instead of blocking downstream tests.
            val remaining = waitForChildCountAtMost(engineScopeJob(engine), beforeChildren)
            assertEquals(beforeChildren, remaining, "engine scope leaked coroutines past server.close()")
        }

    // ── Engine lifecycle ─────────────────────────────────────────────────────

    @Test
    fun engineCloseCancelsEngineScope() =
        runBlocking(Dispatchers.IO) {
            val engine = defaultQuicServerEngine()
            val job = engineScopeJob(engine)
            assertTrue(job.isActive, "engine scope should be active pre-close")
            engine.close()
            assertTrue(job.isCancelled, "engine scope should be cancelled after close()")
        }

    @Test
    fun multipleBindCloseCyclesDoNotAccumulateChildren() =
        runBlocking(Dispatchers.IO) {
            val engine = defaultQuicServerEngine()
            val baseline = engineScopeJob(engine).children.count()

            repeat(10) {
                val server = engine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = opts)
                server.close()
            }

            val after = waitForChildCountAtMost(engineScopeJob(engine), baseline)
            assertEquals(baseline, after, "children accumulated across bind/close cycles — leak")
        }

    // ── Real-connection leak invariants (mirrors what JvmQuicServerTestSuite does) ──

    @Test
    fun echoRoundTripThenCloseLeavesNoActiveChildren() =
        runBlocking(Dispatchers.IO) {
            val serverEngine = defaultQuicServerEngine()
            val clientEngine = defaultQuicEngine()
            val serverScopeJob = engineScopeJob(serverEngine)
            val clientScopeJob = engineScopeJob(clientEngine)
            val serverBaseline = serverScopeJob.children.count()
            val clientBaseline = clientScopeJob.children.count()

            val server = serverEngine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = opts)
            val serverJob =
                launch {
                    server.connections {
                        val stream = withTimeout(3.seconds) { acceptStream() }
                        try {
                            val data = stream.read(5.seconds)
                            if (data is com.ditchoom.buffer.flow.ReadResult.Data) {
                                stream.write(data.buffer, 5.seconds)
                            }
                        } finally {
                            stream.close()
                        }
                    }
                }

            // No delay before connect — the server's receive loop will buffer
            // the client's Initial packet even if connections() hasn't yet been
            // scheduled. If close/handshake ordering breaks this, that's a bug.
            clientEngine.connect("localhost", server.port, opts, timeout = 10.seconds) {
                val stream = openStream()
                val send = BufferFactory.Default.allocate(5)
                send.writeString("hello", com.ditchoom.buffer.Charset.UTF8)
                send.resetForRead()
                stream.write(send, 5.seconds)
                stream.read(5.seconds)
                stream.close()
            }

            serverJob.cancel()
            server.close()

            // Leak check — anything still on the engine scope is a zombie.
            val serverLeak = waitForChildCountAtMost(serverScopeJob, serverBaseline)
            val clientLeak = waitForChildCountAtMost(clientScopeJob, clientBaseline)

            if (serverLeak > serverBaseline || clientLeak > clientBaseline) {
                fail(
                    "leaked coroutines after echo round-trip: " +
                        "server=$serverLeak (baseline $serverBaseline), " +
                        "client=$clientLeak (baseline $clientBaseline)",
                )
            }

            clientEngine.close()
        }

    // ── Concurrent close/handler interaction ────────────────────────────────

    @Test
    fun closeWhileConnectionsBlockingDoesNotDeadlock() =
        runBlocking(Dispatchers.IO) {
            val engine = defaultQuicServerEngine()
            val server = engine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = opts)

            // connections() suspends on an empty channel forever. Must not
            // prevent close() from returning — regardless of whether it's
            // been scheduled yet.
            val handlerJob =
                launch {
                    server.connections {
                        // Unreachable — no driver ever arrives in this test.
                    }
                }

            withTimeout(2.seconds) { server.close() }

            // Handler should unblock via the server's scope cancellation.
            val result = withTimeoutOrNull(2.seconds) { handlerJob.join() }
            assertNotNull(result, "connections() handler did not terminate after server.close()")
        }
}

/**
 * Wait until [job]'s children count drops to [target], yielding to let other
 * coroutines make progress. Returns the observed count at exit — lets the
 * caller fail with a precise delta instead of a bare timeout. The timeout
 * bound is the invariant being asserted ("all children must terminate within
 * N ms"), not an arbitrary sleep.
 */
private suspend fun waitForChildCountAtMost(
    job: Job,
    target: Int,
    timeoutMs: Long = 2_000,
): Int {
    var observed = job.children.count()
    withTimeoutOrNull(timeoutMs) {
        while (observed > target) {
            yield()
            observed = job.children.count()
        }
    }
    return observed
}
