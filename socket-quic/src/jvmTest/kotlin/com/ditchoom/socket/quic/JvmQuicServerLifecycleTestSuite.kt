package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * JVM subclass of [QuicServerLifecycleTestSuite].
 *
 * Inherits every black-box invariant from the base suite, then layers on
 * internal-state assertions that require reflection (engine scope + server
 * child-job introspection). These only run on the JVM because Kotlin
 * reflection-based field access is JVM-only; Apple / Linux native targets
 * still inherit the black-box tests.
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

    override fun serverEngine(): QuicServerEngine =
        try {
            defaultQuicServerEngine()
        } catch (e: Throwable) {
            assumeTrue("Native lib not available: ${e.message}", false)
            throw AssertionError("unreachable")
        }

    override fun clientEngine(): QuicEngine =
        try {
            defaultQuicEngine()
        } catch (e: Throwable) {
            assumeTrue("Native lib not available: ${e.message}", false)
            throw AssertionError("unreachable")
        }

    // ── Reflection helpers — test-only access to engine + server internals ──

    /** Reach into the engine's internal scope via reflection. */
    private fun engineScopeJob(engine: Any): Job {
        val scopeField = engine::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val scope = scopeField.get(engine) as CoroutineScope
        return scope.coroutineContext[Job]!!
    }

    private fun serverChildJob(server: QuicServer): Job {
        val jobField = server::class.java.getDeclaredField("serverJob").apply { isAccessible = true }
        return jobField.get(server) as Job
    }

    // ── JVM-only invariants (reflection-backed) ─────────────────────────────

    @Test
    fun serverCloseCancelsItsChildScope() =
        runBlocking(Dispatchers.IO) {
            val engine = serverEngine()
            val server = engine.bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
            val childJob = serverChildJob(server)
            assertTrue(childJob.isActive, "serverJob should be active while bound")

            server.close()

            assertTrue(childJob.isCancelled, "serverJob should be cancelled after close()")
        }

    @Test
    fun serverCloseLeavesNoActiveChildrenOnEngineScope() =
        runBlocking(Dispatchers.IO) {
            val engine = serverEngine()
            val beforeChildren = engineScopeJob(engine).children.count()
            val server = engine.bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
            server.close()

            val remaining = waitForChildCountAtMost(engineScopeJob(engine), beforeChildren)
            assertEquals(beforeChildren, remaining, "engine scope leaked coroutines past server.close()")
        }

    @Test
    fun engineCloseCancelsEngineScope() =
        runBlocking(Dispatchers.IO) {
            val engine = serverEngine()
            val job = engineScopeJob(engine)
            assertTrue(job.isActive, "engine scope should be active pre-close")
            engine.close()
            assertTrue(job.isCancelled, "engine scope should be cancelled after close()")
        }

    @Test
    fun multipleBindCloseCyclesDoNotAccumulateChildren() =
        runBlocking(Dispatchers.IO) {
            val engine = serverEngine()
            val baseline = engineScopeJob(engine).children.count()

            repeat(10) {
                val server = engine.bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
                server.close()
            }

            val after = waitForChildCountAtMost(engineScopeJob(engine), baseline)
            assertEquals(baseline, after, "children accumulated across bind/close cycles — leak")
        }

    @Test
    fun echoRoundTripThenCloseLeavesNoActiveChildren() =
        runBlocking(Dispatchers.IO) {
            val srvEngine = serverEngine()
            val cliEngine = clientEngine()
            val serverScopeJob = engineScopeJob(srvEngine)
            val clientScopeJob = engineScopeJob(cliEngine)
            val serverBaseline = serverScopeJob.children.count()
            val clientBaseline = clientScopeJob.children.count()

            val server = srvEngine.bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
            val serverJob =
                launch {
                    server.connections {
                        val stream = withTimeout(3.seconds) { acceptStream() }
                        try {
                            val data = stream.read(5.seconds)
                            if (data is ReadResult.Data) {
                                stream.write(data.buffer, 5.seconds)
                            }
                        } finally {
                            stream.close()
                        }
                    }
                }

            cliEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                val stream = openStream()
                val send = BufferFactory.Default.allocate(5)
                send.writeString("hello", Charset.UTF8)
                send.resetForRead()
                stream.write(send, 5.seconds)
                stream.read(5.seconds)
                stream.close()
            }

            serverJob.cancel()
            server.close()

            val serverLeak = waitForChildCountAtMost(serverScopeJob, serverBaseline)
            val clientLeak = waitForChildCountAtMost(clientScopeJob, clientBaseline)

            if (serverLeak > serverBaseline || clientLeak > clientBaseline) {
                fail(
                    "leaked coroutines after echo round-trip: " +
                        "server=$serverLeak (baseline $serverBaseline), " +
                        "client=$clientLeak (baseline $clientBaseline)",
                )
            }

            cliEngine.close()
        }
}

/**
 * Wait until [job]'s children count drops to [target], yielding to let other
 * coroutines make progress. Returns the observed count at exit — lets the
 * caller fail with a precise delta instead of a bare timeout.
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
