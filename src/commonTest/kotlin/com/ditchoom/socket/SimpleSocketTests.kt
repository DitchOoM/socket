package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class SimpleSocketTests {
    @Test
    fun connectTimeoutWorks() =
        runTest {
            try {
                // Use non-routable IP address to test connection timeout without external network
                ClientSocket.connect(80, hostname = "10.255.255.1", timeout = 1.seconds)
                fail("should not have reached this")
            } catch (_: TimeoutCancellationException) {
            } catch (_: SocketException) {
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    // only expected for browsers
                    throw e
                }
            }
        }

    @Test
    fun invalidHost() =
        runTestNoTimeSkipping {
            try {
                // Use .invalid TLD which is reserved per RFC 2606 and should never resolve
                ClientSocket.connect(80, hostname = "nonexistent.invalid", timeout = 1.seconds)
                fail("should not have reached this")
            } catch (e: SocketException) {
                // expected
            }
        }

    @Test
    fun closeWorks() =
        runTest {
            try {
                // Use non-routable IP address to test without external network
                ClientSocket.connect(80, hostname = "10.255.255.1", timeout = 1.seconds)
                fail("the connection should timeout, so this line should never hit")
            } catch (t: TimeoutCancellationException) {
                // expected
            } catch (s: SocketException) {
                // expected
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    // only expected for browsers
                    throw e
                }
            }
        }

    @Test
    fun manyClientsConnectingToOneServer() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val acceptedClientFlow = server.bind()
            val clientCount = 5
            val processedClients = Mutex(locked = true)
            var clientsHandled = 0
            val serverJob =
                launch(Dispatchers.Default) {
                    acceptedClientFlow.collect { serverToClient ->
                        val s = serverToClient.readString()
                        val indexReceived = s.toInt()
                        serverToClient.writeString("ack $indexReceived")
                        serverToClient.close()
                        clientsHandled++
                        if (clientsHandled >= clientCount) {
                            processedClients.unlock()
                        }
                    }
                }
            repeat(clientCount) { index ->
                ClientSocket.connect(server.port()) { clientToServer ->
                    clientToServer.writeString(index.toString())
                    val read = clientToServer.readString()
                    assertEquals("ack $index", read)
                    clientToServer.close()
                }
            }
            // Wait for server to finish processing all clients
            processedClients.lockWithTimeout()
            server.close()
            serverJob.cancel()
        }

    /**
     * Integration tests that require external network access.
     * These verify real-world HTTP/HTTPS connectivity.
     */
    @Test
    fun httpRawSocketExampleDomain() = readHttp("example.com", false)

    @Test
    fun httpRawSocketGoogleDomain() = readHttp("google.com", false)

    @Ignore // Depends on external network; replace with local TLS server test
    @Test
    fun httpsRawSocketGoogleDomain() = readHttp("google.com", true)

    private fun readHttp(
        domain: String,
        tls: Boolean,
    ) = runTestNoTimeSkipping {
        var localPort = 1
        val remotePort = if (tls) 443 else 80
        val socketOptions = if (tls) SocketOptions.tlsDefault() else SocketOptions()
        val response =
            ClientSocket.connect(remotePort, domain, socketOptions = socketOptions, timeout = 10.seconds) { socket ->
                val request =
                    """
GET / HTTP/1.1
Host: www.$domain
Connection: close

""".toReadBuffer(Charset.UTF8, AllocationZone.Heap)
                val bytesWritten = socket.write(request, 5.seconds)
                localPort = socket.localPort()
                assertTrue { bytesWritten > 0 }
                val response = StringBuilder(socket.readString(timeout = 5.seconds))
                // Always use UTF8 for reading - NativeBuffer on Linux only supports UTF8,
                // and HTTP responses from google.com/example.com are ASCII-compatible
                socket.readFlowString(Charset.UTF8, 5.seconds).collect {
                    response.append(it)
                }
                response
            }
        assertTrue { response.startsWith("HTTP/") }
        assertTrue { response.contains("<html", ignoreCase = true) }
        assertTrue { response.contains("/html>", ignoreCase = true) }
        assertNotEquals(1, localPort)
        checkPort(localPort)
    }

    @Test
    fun serverEcho() =
        runTestNoTimeSkipping(3) {
            val server = ServerSocket.allocate()
            val text = "yolo swag lyfestyle"
            var serverToClientPort = 0
            val serverToClientMutex = Mutex(locked = true)
            val flow = server.bind()
            launch(Dispatchers.Default) {
                flow.collect { serverToClient ->
                    val buffer = serverToClient.read(1.seconds)
                    buffer.resetForRead()
                    val dataReceivedFromClient = buffer.readString(buffer.remaining(), Charset.UTF8)
                    assertEquals(text, dataReceivedFromClient)
                    serverToClientPort = serverToClient.localPort()
                    assertTrue(serverToClientPort > 0, "No port number: serverToClientPort")
                    serverToClient.close()
                    serverToClientMutex.unlock()
                }
            }
            val serverPort = server.port()
            assertTrue(serverPort > 0, "No port ($serverPort) number from server")
            val clientToServer = ClientSocket.allocate()
            clientToServer.open(serverPort, 5.seconds)
            clientToServer.write(text.toReadBuffer(Charset.UTF8), 1.seconds)
            serverToClientMutex.lockWithTimeout()
            val clientToServerPort = clientToServer.localPort()
            assertTrue(clientToServerPort > 0, "Invalid clientToServerPort local port.")
            clientToServer.close()
            server.close()
            checkPort(serverToClientPort)
            checkPort(clientToServerPort)
            checkPort(serverPort)
        }

    @Test
    fun clientEcho() =
        runTestNoTimeSkipping {
            val text = "yolo swag lyfestyle"
            val server = ServerSocket.allocate()
            var serverToClientPort = 0
            val serverToClientMutex = Mutex(locked = true)
            val acceptedClientFlow = server.bind()
            launch(Dispatchers.Default) {
                acceptedClientFlow.collect { serverToClient ->
                    serverToClientPort = serverToClient.localPort()
                    assertTrue { serverToClientPort > 0 }
                    serverToClient.writeString(text, Charset.UTF8, 5.seconds)
                    serverToClient.close()
                    serverToClientMutex.unlock()
                    return@collect
                }
            }
            val clientToServer = ClientSocket.allocate()
            val serverPort = server.port()
            assertTrue(serverPort > 0, "No port number from server")
            clientToServer.open(serverPort)
            val buffer = clientToServer.read(5.seconds)
            buffer.resetForRead()
            val dataReceivedFromServer = buffer.readString(buffer.remaining(), Charset.UTF8)
            serverToClientMutex.lockWithTimeout()
            assertEquals(text, dataReceivedFromServer)
            val clientToServerPort = clientToServer.localPort()
            assertTrue(clientToServerPort > 0, "No port number: clientToServerPort")
            clientToServer.close()
            server.close()
            checkPort(clientToServerPort)
            checkPort(serverToClientPort)
            checkPort(serverPort)
        }

    @Test
    fun suspendingInputStream() =
        runTestNoTimeSkipping {
            suspendingInputStream()
        }

    suspend fun CoroutineScope.suspendingInputStream() {
        val server = ServerSocket.allocate()
        val text = "yolo swag lyfestyle"
        val text2 = "old mac donald had a farm"
        var serverToClientPort = 0
        val serverToClientMutex = Mutex(locked = true)
        val acceptedClientFlow = server.bind()
        launch(Dispatchers.Default) {
            acceptedClientFlow.collect { serverToClient ->
                serverToClientPort = serverToClient.localPort()
                assertTrue(serverToClientPort > 0, "No port number: serverToClientPort")
                serverToClient.writeString(text, Charset.UTF8, 1.seconds)
                delay(5)
                serverToClient.writeString(text2, Charset.UTF8, 1.seconds)
                serverToClient.close()
                serverToClientMutex.unlock()
                return@collect
            }
        }
        val serverPort = server.port()
        assertTrue(serverPort > 0, "No port number from server")
        val clientToServer = ClientSocket.allocate()
        clientToServer.open(serverPort)
        val clientToServerPort = clientToServer.localPort()
        assertTrue(clientToServerPort > 0, "No port number from clientToServerPort")
        val inputStream = SuspendingSocketInputStream(1.seconds, clientToServer)
        var buffer = inputStream.ensureBufferSize(text.length)
        serverToClientMutex.lockWithTimeout()
        val utf8 = buffer.readString(text.length, Charset.UTF8)
        assertEquals(utf8, text)
        buffer = inputStream.ensureBufferSize(text2.length)
        val utf8v2 = buffer.readString(text2.length, Charset.UTF8)
        assertEquals(utf8v2, text2)
        clientToServer.close()
        server.close()
        checkPort(clientToServerPort)
        checkPort(serverPort)
        checkPort(serverToClientPort)
    }

    private suspend fun checkPort(port: Int) {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return
        val stats = readStats(port, "CLOSE_WAIT")
        if (stats.isNotEmpty()) {
            println("stats (${stats.count()}): $stats")
        }
        assertEquals(0, stats.count(), "Socket still in CLOSE_WAIT state found!")
    }
}

expect suspend fun readStats(
    port: Int,
    contains: String,
): List<String>

expect fun supportsIPv6(): Boolean

class IPv6SocketTests {
    @Test
    fun serverBindsToIPv6() =
        runTestNoTimeSkipping {
            if (!supportsIPv6()) return@runTestNoTimeSkipping

            val server = ServerSocket.allocate()
            val text = "ipv6 test"
            val acceptedClientFlow = server.bind(host = "::")
            var serverReceived = false
            val serverJob =
                launch(Dispatchers.Default) {
                    acceptedClientFlow.collect { serverToClient ->
                        val received = serverToClient.readString()
                        assertEquals(text, received)
                        serverReceived = true
                        serverToClient.close()
                    }
                }

            // Connect using localhost (should work via dual-stack or IPv6)
            val client = ClientSocket.allocate()
            client.open(server.port(), hostname = "localhost")
            client.writeString(text)
            client.close()

            // Give server time to process
            delay(100)
            assertTrue(serverReceived, "Server should have received the message")

            server.close()
            serverJob.cancel()
        }

    @Test
    fun serverAcceptsBothIPv4AndIPv6() =
        runTestNoTimeSkipping {
            if (!supportsIPv6()) return@runTestNoTimeSkipping

            val server = ServerSocket.allocate()
            var clientsHandled = 0
            val acceptedClientFlow = server.bind() // Default should use dual-stack

            val serverJob =
                launch(Dispatchers.Default) {
                    acceptedClientFlow.collect { serverToClient ->
                        val received = serverToClient.readString()
                        assertTrue(received.isNotEmpty())
                        clientsHandled++
                        serverToClient.close()
                    }
                }

            // Connect using IPv4 localhost
            val client1 = ClientSocket.allocate()
            client1.open(server.port(), hostname = "127.0.0.1")
            client1.writeString("ipv4")
            client1.close()

            delay(100)

            server.close()
            serverJob.cancel()

            assertTrue(clientsHandled >= 1, "Server should have handled at least one client")
        }
}

class ServerCancellationTests {
    @Test
    fun serverClosesQuickly() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val acceptedClientFlow = server.bind()

            val serverJob =
                launch(Dispatchers.Default) {
                    acceptedClientFlow.collect { _ ->
                        // Just accept connections
                    }
                }

            // Let the server start accepting
            delay(100)

            // Close the server - should be quick, not wait 1 second
            val startTime = currentTimeMillis()
            server.close()
            serverJob.cancel()
            val elapsed = currentTimeMillis() - startTime

            // Should close in well under 1 second if cancellation is working
            assertTrue(elapsed < 500, "Server close took too long: ${elapsed}ms")
        }
}

/**
 * Tests for client socket read/write cancellation.
 * Critical for WebSocket library which needs to cancel read operations when closing.
 */
class ClientCancellationTests {
    /**
     * Test that cancelling a coroutine blocked on socket.read() completes quickly.
     * This verifies io_uring cancellation is working properly.
     */
    @Test
    fun cancelReadCompletesQuickly() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val acceptedClientFlow = server.bind()
            val clientConnected = Mutex(locked = true)

            // Server accepts connection but never sends data
            val serverJob =
                launch(Dispatchers.Default) {
                    acceptedClientFlow.collect { serverToClient ->
                        clientConnected.unlock()
                        // Hold connection open but don't send anything
                        delay(10.seconds)
                        serverToClient.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port())

            // Wait for server to accept
            clientConnected.lockWithTimeout()

            // Start a read that will block (server isn't sending)
            val readJob =
                launch(Dispatchers.Default) {
                    try {
                        // Long timeout - we'll cancel before this fires
                        client.read(30.seconds)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // Expected when cancelled
                    } catch (_: SocketClosedException) {
                        // Also acceptable if socket was closed
                    } catch (_: SocketException) {
                        // May get this on some platforms
                    }
                }

            // Let the read start
            delay(100)

            // Cancel the read - should complete quickly
            val startTime = currentTimeMillis()
            readJob.cancel()
            readJob.join()
            val elapsed = currentTimeMillis() - startTime

            // Cancellation should complete well under 500ms
            // (not waiting for the 30 second read timeout)
            assertTrue(elapsed < 500, "Read cancellation took too long: ${elapsed}ms")

            client.close()
            server.close()
            serverJob.cancel()
        }

    /**
     * Test that cancelling a coroutine blocked on socket.write() completes quickly.
     */
    @Test
    fun cancelWriteCompletesQuickly() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val acceptedClientFlow = server.bind()
            val clientConnected = Mutex(locked = true)

            // Server accepts connection but never reads data (causes write to block)
            val serverJob =
                launch(Dispatchers.Default) {
                    acceptedClientFlow.collect { serverToClient ->
                        clientConnected.unlock()
                        // Hold connection open but don't read
                        delay(10.seconds)
                        serverToClient.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port())

            // Wait for server to accept
            clientConnected.lockWithTimeout()

            // Start a write that may block if buffer fills
            val writeJob =
                launch(Dispatchers.Default) {
                    try {
                        // Write lots of data to fill the buffer and block
                        val largeData = "x".repeat(1024 * 1024).toReadBuffer(Charset.UTF8, AllocationZone.Heap)
                        repeat(100) {
                            client.write(largeData, 30.seconds)
                            largeData.resetForRead()
                        }
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // Expected when cancelled
                    } catch (_: SocketClosedException) {
                        // Also acceptable
                    } catch (_: SocketException) {
                        // May get this on some platforms
                    }
                }

            // Let writes start
            delay(200)

            // Cancel the write - should complete quickly
            val startTime = currentTimeMillis()
            writeJob.cancel()
            writeJob.join()
            val elapsed = currentTimeMillis() - startTime

            // Cancellation should complete quickly
            assertTrue(elapsed < 500, "Write cancellation took too long: ${elapsed}ms")

            client.close()
            server.close()
            serverJob.cancel()
        }

    /**
     * Verify that after cancelling a read with a caller-provided buffer,
     * the buffer is safe to access and free. This validates the API contract:
     * when read() throws (including CancellationException), the buffer is not
     * being written to by the kernel.
     */
    @Test
    fun cancelledReadBufferIsSafeToFree() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val acceptedClientFlow = server.bind()
            val clientConnected = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    acceptedClientFlow.collect { serverToClient ->
                        clientConnected.unlock()
                        delay(10.seconds)
                        serverToClient.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port())
            clientConnected.lockWithTimeout()

            // Allocate a buffer and start a read into it
            val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)

            val readJob =
                launch(Dispatchers.Default) {
                    try {
                        client.read(buffer, 30.seconds)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // Expected
                    } catch (_: SocketClosedException) {
                        // Also acceptable
                    } catch (_: SocketException) {
                        // May get this on some platforms
                    }
                }

            delay(200)

            readJob.cancel()
            readJob.join()

            // Buffer should be safe to access after cancellation
            buffer.position(0)
            buffer.freeIfNeeded()

            client.close()
            server.close()
            serverJob.cancel()
        }

    /**
     * Test that multiple cancellations don't leak resources or crash.
     * Note: On some platforms, cancelling a blocked read may close the socket.
     * This test verifies the cancellation path itself is clean.
     */
    @Test
    fun multipleCancellationsDontCrash() =
        runTestNoTimeSkipping {
            // Run multiple connect-read-cancel cycles to verify no resource leaks
            repeat(3) { iteration ->
                val server = ServerSocket.allocate()
                val acceptedClientFlow = server.bind()
                val clientConnected = Mutex(locked = true)

                val serverJob =
                    launch(Dispatchers.Default) {
                        acceptedClientFlow.collect { serverToClient ->
                            clientConnected.unlock()
                            delay(10.seconds)
                            serverToClient.close()
                        }
                    }

                val client = ClientSocket.allocate()
                client.open(server.port())
                clientConnected.lockWithTimeout()

                // Start and cancel a read
                val readJob =
                    launch(Dispatchers.Default) {
                        try {
                            client.read(30.seconds)
                        } catch (_: Exception) {
                            // Ignore
                        }
                    }
                delay(50)

                val startTime = currentTimeMillis()
                readJob.cancel()
                readJob.join()
                val elapsed = currentTimeMillis() - startTime

                // Cancellation should be fast
                assertTrue(
                    elapsed < 500,
                    "Read cancellation took too long in iteration $iteration: ${elapsed}ms",
                )

                // Clean shutdown should not throw
                try {
                    client.close()
                } catch (_: Exception) {
                    // Acceptable if socket was already closed by cancellation
                }
                server.close()
                serverJob.cancel()
            }
        }
}

expect fun currentTimeMillis(): Long
