package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Shared QUIC server test suite. Each platform extends this with its engines.
 *
 * Guarantees test parity — same tests run on JVM, Linux, and Apple.
 *
 * **Lifecycle:** platforms implement [withServerEngine] / [withClientEngine]
 * block-takers (typically delegating to [withQuicServerEngine] /
 * [withQuicEngine]) so each engine is closed in `finally` on every exit
 * path. The previous `serverEngine()` / `clientEngine()` factory methods
 * leaked engines on test failure (the per-platform `@AfterTest` tracker
 * was a stopgap); scope-only construction removes the foot-gun entirely.
 * See `socket-quic/DRIVER_REDESIGN.md` → "Engine lifecycle".
 */
abstract class QuicServerTestSuite {
    /**
     * Provide a [QuicServerEngine] scoped to [block]. The engine is closed
     * automatically when [block] returns, throws, or is cancelled.
     */
    abstract suspend fun <R> withServerEngine(block: suspend (QuicServerEngine) -> R): R

    /**
     * Provide a [QuicEngine] scoped to [block]. The engine is closed
     * automatically when [block] returns, throws, or is cancelled.
     */
    abstract suspend fun <R> withClientEngine(block: suspend (QuicEngine) -> R): R

    abstract fun testTlsConfig(): QuicTlsConfig

    val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    @Test
    fun serverBindsAndReportsPort() =
        runQuicTest {
            withServerEngine { engine ->
                val server = engine.bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
                try {
                    assertTrue(server.port > 0, "Server should bind to a real port")
                } finally {
                    server.close()
                }
            }
        }

    @Test
    fun serverAcceptsConnection() =
        runQuicTest {
            // Handler-immediate pattern: see rapidBindConnectCloseCyclesAreClean
            // below for the long story. tl;dr — delay(2.seconds) in the handler
            // deadlocks driver shutdown on GH ubuntu-24.04 because handlers are
            // launched on the engine's scope (not serverJob's). Letting the
            // handler return immediately lets the framework's finally{conn.close()}
            // run on the natural path.
            withServerEngine { srvEngine ->
                withClientEngine { cliEngine ->
                    val server = srvEngine.bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
                    val handlerRan = CompletableDeferred<Unit>()

                    val serverJob =
                        launch {
                            server.connections {
                                handlerRan.complete(Unit)
                            }
                        }

                    try {
                        cliEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                            // Empty — connect returns when block returns.
                        }

                        kotlinx.coroutines.withTimeout(10.seconds) { handlerRan.await() }
                    } finally {
                        serverJob.cancel()
                        server.close()
                    }
                }
            }
        }

    @Test
    fun echoSingleStream() =
        runQuicTest {
            withServerEngine { srvEngine ->
                withClientEngine { cliEngine ->
                    val server = srvEngine.bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
                    val echoResult = CompletableDeferred<String>()

                    val serverJob =
                        launch {
                            server.connections {
                                val stream = acceptStream()
                                val data = stream.read(5.seconds)
                                if (data is ReadResult.Data) {
                                    stream.write(data.buffer, 5.seconds)
                                }
                                stream.close()
                            }
                        }
                    delay(100)

                    val clientJob =
                        launch {
                            cliEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                                val stream = openStream()
                                val sendBuf = BufferFactory.deterministic().allocate(11)
                                sendBuf.writeString("hello quic!", Charset.UTF8)
                                sendBuf.resetForRead()
                                stream.write(sendBuf, 5.seconds)

                                val response = stream.read(5.seconds)
                                if (response is ReadResult.Data) {
                                    echoResult.complete(response.buffer.readString(response.buffer.remaining(), Charset.UTF8))
                                } else {
                                    echoResult.complete("no_data")
                                }
                                stream.close()
                            }
                        }

                    try {
                        val result = kotlinx.coroutines.withTimeout(10.seconds) { echoResult.await() }
                        assertEquals("hello quic!", result)
                    } finally {
                        clientJob.cancel()
                        serverJob.cancel()
                        server.close()
                    }
                }
            }
        }

    @Test
    fun multipleConnections() =
        runQuicTest {
            // Handler-immediate pattern (see serverAcceptsConnection / rapidBindConnect…).
            withServerEngine { srvEngine ->
                withClientEngine { cliEngine ->
                    val server = srvEngine.bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
                    val count = 3
                    val handlersRan = CompletableDeferred<Unit>()
                    var connected = 0
                    val lock = Mutex()

                    val serverJob =
                        launch {
                            server.connections {
                                lock.withLock {
                                    connected++
                                    if (connected >= count) handlersRan.complete(Unit)
                                }
                            }
                        }

                    val clientJobs =
                        (1..count).map {
                            launch {
                                cliEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                                    // Empty block.
                                }
                            }
                        }

                    try {
                        kotlinx.coroutines.withTimeout(10.seconds) { handlersRan.await() }
                    } finally {
                        clientJobs.forEach { it.cancel() }
                        serverJob.cancel()
                        server.close()
                    }
                }
            }
        }

    @Test
    fun serverCloseIsClean() =
        runQuicTest {
            withServerEngine { engine ->
                val server = engine.bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
                assertTrue(server.port > 0)
                server.close()
                // Should not throw or hang — clean shutdown
            }
        }

    /**
     * Regression: server.close() must stop the receive loop BEFORE destroying drivers
     * and BEFORE freeing recv buffers. On Linux/io_uring, getting this wrong races with
     * (a) the receive loop routing a packet to an already-destroyed driver (SIGSEGV) or
     * (b) the kernel finishing an in-flight io_uring SQE write into already-freed
     * recv buffers (glibc "malloc(): unsorted double linked list corrupted"). Single-cycle
     * runs in serverAcceptsConnection caught this ~17% of the time; 10 rapid cycles in
     * one test push that to >85%, so a regression fires reliably here.
     */
    @Test
    fun rapidBindConnectCloseCyclesAreClean() =
        runQuicTest {
            // Surgical fix: this specific test hangs on GH Actions ubuntu-24.04 CI
            // when the handler holds open with delay(500). With the original code
            // (delay-based hold), the JvmQuicServer.connections() launches the
            // handler on the engine's scope, not serverJob's — when the test
            // serverJob.cancel()s, the handler keeps running, blocking server.close()
            // via driver.destroy().join() on slower CI scheduling. 10 rapid cycles
            // amplify the race past the 5s outer budget.
            //
            // Fix: handler returns immediately after signaling handlerRan. The
            // JvmQuicServer.connections() impl wraps the handler in
            // try { handler() } finally { conn.close() }, so a returning handler
            // triggers the framework's clean-shutdown path. Client uses an empty
            // block for the same reason. cfadcdc made connections() use
            // coroutineScope { } so the for-loop is also bound to serverJob;
            // serverJob.cancel() now cleanly tears down the iterator AND any
            // in-flight handler.
            //
            // Engines are scoped across the 10 cycles — only one server engine and
            // one client engine for the whole test, not 20 fresh ones. Cheaper and
            // matches the canonical pattern.
            withServerEngine { srvEngine ->
                withClientEngine { cliEngine ->
                    repeat(10) {
                        val server = srvEngine.bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
                        val handlerRan = CompletableDeferred<Unit>()
                        val serverJob =
                            launch {
                                server.connections {
                                    handlerRan.complete(Unit)
                                    // Return immediately; framework closes conn via finally.
                                }
                            }
                        try {
                            cliEngine.connect("localhost", server.port, testQuicOptions, timeout = 5.seconds) {
                                // Empty block — connect returns when block returns.
                            }
                            kotlinx.coroutines.withTimeout(5.seconds) { handlerRan.await() }
                        } finally {
                            serverJob.cancel()
                            server.close()
                        }
                    }
                }
            }
        }
}
