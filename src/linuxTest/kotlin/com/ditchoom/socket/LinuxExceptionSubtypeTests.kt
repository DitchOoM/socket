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
 * Linux-specific tests that strictly assert sealed exception subtypes.
 *
 * Linux io_uring produces deterministic errno values, so we assert
 * the exact subtype for each error condition. Each test catches ONE type.
 */
class LinuxExceptionSubtypeTests {
    @Test
    fun connectionRefused_isRefused() =
        runTestNoTimeSkipping {
            val port = 59500 + kotlin.random.Random.nextInt(499)
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
            assertTrue(ex.platformError != null, "platformError should be set on Linux")
        }

    @Test
    fun readAfterGracefulClose_isEndOfStream() =
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

            client.readString(timeout = 2.seconds)

            // On Linux, graceful close + read produces bytesRead == 0 → EndOfStream
            val ex =
                try {
                    client.read(2.seconds)
                    fail("Should have thrown")
                } catch (e: SocketClosedException) {
                    e
                }
            assertIs<SocketClosedException.EndOfStream>(ex)

            client.close()
            server.close()
            serverJob.cancel()
        }

    @Test
    fun writeAfterPeerClose_isConnectionResetOrBrokenPipe() =
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
                            "x".repeat(4096).toReadBuffer(com.ditchoom.buffer.Charset.UTF8),
                            1.seconds,
                        )
                        kotlinx.coroutines.delay(2)
                    }
                    fail("Should have thrown when writing to closed connection")
                } catch (e: SocketClosedException) {
                    e
                }

            // Linux io_uring maps ECONNRESET → ConnectionReset, EPIPE → BrokenPipe
            assertTrue(
                ex is SocketClosedException.ConnectionReset ||
                    ex is SocketClosedException.BrokenPipe,
                "Expected ConnectionReset or BrokenPipe, got: ${ex::class.simpleName}",
            )

            client.close()
            server.close()
            serverJob.cancel()
        }

    @Test
    fun dnsFailure_hostnamePreserved() =
        runTestNoTimeSkipping {
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(port = 80, timeout = 5.seconds, hostname = "nonexistent.linux.test.invalid")
                    socket.close()
                    fail("Should have thrown")
                } catch (e: SocketUnknownHostException) {
                    e
                }
            assertIs<SocketUnknownHostException>(ex)
            assertTrue(
                ex.hostname == "nonexistent.linux.test.invalid",
                "hostname should be preserved, got: ${ex.hostname}",
            )
        }

}
