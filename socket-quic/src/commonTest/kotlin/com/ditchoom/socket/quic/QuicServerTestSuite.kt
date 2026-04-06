package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
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
 */
abstract class QuicServerTestSuite {
    abstract fun serverEngine(): QuicServerEngine
    abstract fun clientEngine(): QuicEngine
    abstract fun testTlsConfig(): QuicTlsConfig

    val testQuicOptions = QuicOptions(
        alpnProtocols = listOf("test"),
        verifyPeer = false,
        idleTimeout = 10.seconds,
    )

    @Test
    fun serverBindsAndReportsPort() = runQuicTest {
        val server = serverEngine().bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
        try {
            assertTrue(server.port > 0, "Server should bind to a real port")
        } finally {
            server.close()
        }
    }

    @Test
    fun serverAcceptsConnection() = runQuicTest {
        val server = serverEngine().bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
        val handlerRan = CompletableDeferred<Unit>()

        val serverJob = launch {
            server.connections {
                handlerRan.complete(Unit)
                delay(2.seconds)
            }
        }
        delay(100)

        val clientJob = launch {
            clientEngine().connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                delay(2.seconds)
            }
        }

        kotlinx.coroutines.withTimeout(10.seconds) { handlerRan.await() }

        clientJob.cancel()
        serverJob.cancel()
        server.close()
    }

    @Test
    fun echoSingleStream() = runQuicTest {
        val server = serverEngine().bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
        val echoResult = CompletableDeferred<String>()

        val serverJob = launch {
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

        val clientJob = launch {
            clientEngine().connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                val stream = openStream()
                val sendBuf = BufferFactory.Default.allocate(11)
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
    fun multipleConnections() = runQuicTest {
        val server = serverEngine().bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
        val count = 3
        val handlersRan = CompletableDeferred<Unit>()
        var connected = 0
        val lock = kotlinx.coroutines.sync.Mutex()

        val serverJob = launch {
            server.connections {
                lock.withLock {
                    connected++
                    if (connected >= count) handlersRan.complete(Unit)
                }
                delay(2.seconds)
            }
        }
        delay(100)

        val clientJobs = (1..count).map {
            launch {
                clientEngine().connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                    delay(2.seconds)
                }
            }
        }

        kotlinx.coroutines.withTimeout(10.seconds) { handlersRan.await() }

        clientJobs.forEach { it.cancel() }
        serverJob.cancel()
        server.close()
    }

    @Test
    fun serverCloseIsClean() = runQuicTest {
        val server = serverEngine().bind(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions)
        assertTrue(server.port > 0)
        server.close()
        // Should not throw or hang — clean shutdown
    }
}
