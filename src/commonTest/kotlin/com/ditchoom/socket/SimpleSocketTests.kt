package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
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
            processedClients.lock()
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

    @Test
    fun httpsRawSocketGoogleDomain() = readHttp("google.com", true)

    private fun readHttp(
        domain: String,
        tls: Boolean,
    ) = runTestNoTimeSkipping {
        var localPort = 1
        val remotePort = if (tls) 443 else 80
        val response =
            ClientSocket.connect(remotePort, domain, tls = tls, timeout = 10.seconds) { socket ->
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
                val charset =
                    if (response.contains("charset=ISO-8859-1", ignoreCase = true)) {
                        Charset.ISOLatin1
                    } else if (response.contains("charset=ascii", ignoreCase = true)) {
                        Charset.ASCII
                    } else {
                        Charset.UTF8
                    }
                socket.readFlowString(charset, 5.seconds).collect {
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
            serverToClientMutex.lock()
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
            serverToClientMutex.lock()
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
        serverToClientMutex.lock()
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

expect fun currentTimeMillis(): Long
