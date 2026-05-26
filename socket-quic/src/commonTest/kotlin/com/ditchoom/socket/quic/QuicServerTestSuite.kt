package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
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
 */
abstract class QuicServerTestSuite {
    abstract fun serverEngine(): QuicServerEngine

    abstract fun clientEngine(): QuicEngine

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
            val server = serverEngine().bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
            try {
                assertTrue(server.port > 0, "Server should bind to a real port")
            } finally {
                server.close()
            }
        }

    @Test
    fun serverAcceptsConnection() =
        runQuicTest {
            val server = serverEngine().bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
            val handlerRan = CompletableDeferred<Unit>()

            val serverJob =
                launch {
                    server.connections {
                        handlerRan.complete(Unit)
                        // Hold the handler open until the test cancels this coroutine —
                        // reactive (vs. delay(2s) which was just guessing).
                        awaitCancellation()
                    }
                }

            val clientJob =
                launch {
                    clientEngine().connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                        // Same — stay connected until cancelled. The test races handlerRan
                        // against a 10s outer timeout and cancels both jobs once satisfied.
                        awaitCancellation()
                    }
                }

            kotlinx.coroutines.withTimeout(10.seconds) { handlerRan.await() }

            clientJob.cancel()
            serverJob.cancel()
            server.close()
        }

    @Test
    fun echoSingleStream() =
        runQuicTest {
            val server = serverEngine().bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
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

            val clientJob =
                launch {
                    clientEngine().connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
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

            val result = kotlinx.coroutines.withTimeout(10.seconds) { echoResult.await() }
            assertEquals("hello quic!", result)

            clientJob.cancel()
            serverJob.cancel()
            server.close()
        }

    @Test
    fun multipleConnections() =
        runQuicTest {
            val server = serverEngine().bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
            val count = 3
            val handlersRan = CompletableDeferred<Unit>()
            var connected = 0
            val lock = kotlinx.coroutines.sync.Mutex()

            val serverJob =
                launch {
                    server.connections {
                        lock.withLock {
                            connected++
                            if (connected >= count) handlersRan.complete(Unit)
                        }
                        awaitCancellation()
                    }
                }

            val clientJobs =
                (1..count).map {
                    launch {
                        clientEngine().connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                            awaitCancellation()
                        }
                    }
                }

            kotlinx.coroutines.withTimeout(10.seconds) { handlersRan.await() }

            clientJobs.forEach { it.cancel() }
            serverJob.cancel()
            server.close()
        }

    @Test
    fun serverCloseIsClean() =
        runQuicTest {
            val server = serverEngine().bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
            assertTrue(server.port > 0)
            server.close()
            // Should not throw or hang — clean shutdown
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
            repeat(10) { iteration ->
                val server = serverEngine().bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
                val handlerRan = CompletableDeferred<Unit>()
                val serverJob =
                    launch {
                        server.connections {
                            handlerRan.complete(Unit)
                            awaitCancellation()
                        }
                    }
                val clientJob =
                    launch {
                        clientEngine().connect("localhost", server.port, testQuicOptions, timeout = 5.seconds) {
                            awaitCancellation()
                        }
                    }
                kotlinx.coroutines.withTimeout(5.seconds) { handlerRan.await() }
                clientJob.cancel()
                serverJob.cancel()
                server.close()
            }
        }
}
