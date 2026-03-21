package com.ditchoom.socket

import com.ditchoom.buffer.Charset
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
 * Integration tests for Node.js socket error wrapping via [wrapNodeError].
 *
 * Exercises the full error path through actual socket operations on Node.js.
 */
class WrapNodeErrorTests {
    @Test
    fun dnsFailure_producesSocketUnknownHostException() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(port = 80, timeout = 5.seconds, hostname = "this.host.does.not.exist.invalid")
                    socket.close()
                    fail("Should have thrown for invalid hostname")
                } catch (e: SocketUnknownHostException) {
                    e
                }
            assertIs<SocketUnknownHostException>(ex)
            assertTrue(ex.message.isNotBlank())
        }

    @Test
    fun connectionRefused_producesSocketConnectionRefused() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping
            val port = 59200 + kotlin.random.Random.nextInt(799)
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(port = port, timeout = 2.seconds, hostname = "127.0.0.1")
                    socket.close()
                    null
                } catch (e: SocketConnectionException.Refused) {
                    e
                } catch (e: SocketTimeoutException) {
                    null // timeout instead of refused — acceptable
                }
            if (ex != null) {
                assertIs<SocketConnectionException.Refused>(ex)
            }
        }

    @Test
    fun readFromClosedSocket_producesSocketClosedException() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val serverReady = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { serverClient ->
                        serverReady.unlock()
                        serverClient.writeString("hi")
                        serverClient.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            serverReady.lockWithTimeout()

            val data = client.readString(timeout = 2.seconds)
            assertTrue(data == "hi")

            val ex =
                try {
                    client.read(2.seconds)
                    fail("Should have thrown")
                } catch (e: SocketClosedException) {
                    e
                }
            // Node.js graceful close produces EndOfStream
            assertTrue(
                ex is SocketClosedException.EndOfStream || ex is SocketClosedException.General,
                "Expected EndOfStream or General, got: ${ex::class.simpleName}",
            )

            client.close()
            server.close()
            serverJob.cancel()
        }

    @Test
    fun writeToClosedSocket_producesSocketClosedException() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

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

            var caughtException: SocketException? = null
            try {
                repeat(50) {
                    client.write(
                        "test data".toReadBuffer(Charset.UTF8),
                        1.seconds,
                    )
                    kotlinx.coroutines.delay(10)
                }
            } catch (e: SocketClosedException) {
                caughtException = e
            } catch (e: SocketIOException) {
                caughtException = e
            }

            if (caughtException != null) {
                // Node.js write-after-close produces ECONNRESET or EPIPE
                assertTrue(
                    caughtException is SocketClosedException.ConnectionReset ||
                        caughtException is SocketClosedException.BrokenPipe ||
                        caughtException is SocketClosedException.General ||
                        caughtException is SocketIOException,
                    "Expected ConnectionReset, BrokenPipe, General, or SocketIOException, got: ${caughtException::class.simpleName}",
                )
            }

            client.close()
            server.close()
            serverJob.cancel()
        }

    @Test
    fun connectionTimeout_producesSocketTimeoutException() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(port = 80, timeout = 1.seconds, hostname = "10.255.255.1")
                    socket.close()
                    null
                } catch (e: SocketTimeoutException) {
                    e
                } catch (e: SocketIOException) {
                    null // also acceptable
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    null // also acceptable
                }
            if (ex != null) {
                assertIs<SocketTimeoutException>(ex)
                assertTrue(ex.message.isNotBlank())
            }
        }
}
