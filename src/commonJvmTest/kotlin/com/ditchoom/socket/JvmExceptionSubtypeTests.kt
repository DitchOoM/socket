package com.ditchoom.socket

import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * JVM-specific tests that strictly assert sealed exception subtypes.
 *
 * These tests know the exact JVM wrapping behavior and assert the specific
 * subtype, not just the parent sealed class. Each test catches ONE type.
 */
class JvmExceptionSubtypeTests {
    @Test
    fun connectionRefused_isSocketConnectionExceptionRefused() =
        runTestNoTimeSkipping {
            val port = 59400 + kotlin.random.Random.nextInt(599)
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(port = port, timeout = 2.seconds, hostname = "127.0.0.1")
                    socket.close()
                    fail("Should have thrown for connection refused")
                } catch (e: SocketConnectionException.Refused) {
                    e
                }
            assertIs<SocketConnectionException.Refused>(ex)
            assertTrue(ex.platformError != null, "platformError should be set")
        }

    @Test
    fun readAfterPeerClose_isEndOfStream() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val serverReady = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { serverClient ->
                        serverReady.unlock()
                        serverClient.writeString("data")
                        serverClient.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            serverReady.lockWithTimeout()

            client.readString(timeout = 2.seconds) // consume the sent data

            val ex =
                try {
                    client.read(2.seconds)
                    fail("Should have thrown")
                } catch (e: SocketClosedException) {
                    e
                }

            // On JVM, reading after graceful peer close produces EndOfStream
            assertIs<SocketClosedException.EndOfStream>(ex)

            client.close()
            server.close()
            serverJob.cancel()
        }

    @Test
    fun dnsFailure_hostnameIsPreserved() =
        runTestNoTimeSkipping {
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(port = 80, timeout = 5.seconds, hostname = "nonexistent.jvm.test.invalid")
                    socket.close()
                    fail("Should have thrown")
                } catch (e: SocketUnknownHostException) {
                    e
                }
            assertIs<SocketUnknownHostException>(ex)
            assertTrue(
                ex.hostname == "nonexistent.jvm.test.invalid" || ex.message.contains("nonexistent.jvm.test.invalid"),
                "Hostname should be preserved, got: hostname=${ex.hostname}, message=${ex.message}",
            )
        }

    @Test
    fun brokenPipeOrReset_isSocketClosedSubtype() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val clientConnected = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        clientConnected.unlock()
                        client.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            clientConnected.lockWithTimeout()
            kotlinx.coroutines.delay(200)

            val ex =
                try {
                    repeat(200) {
                        client.write(
                            "x".repeat(8192).toReadBuffer(com.ditchoom.buffer.Charset.UTF8),
                            1.seconds,
                        )
                        kotlinx.coroutines.delay(2)
                    }
                    fail("Should have thrown when writing to closed connection")
                } catch (e: SocketClosedException) {
                    e
                }

            // On JVM, writing to closed peer produces BrokenPipe or ConnectionReset
            assertTrue(
                ex is SocketClosedException.BrokenPipe ||
                    ex is SocketClosedException.ConnectionReset,
                "Expected BrokenPipe or ConnectionReset, got: ${ex::class.simpleName}",
            )

            client.close()
            server.close()
            serverJob.cancel()
        }
}
