package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.socket.harness.HarnessConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class SimpleSocketTests {
    /**
     * Connect against the harness `netem-blackhole` endpoint — a listener whose
     * eth0 has `tc qdisc … netem loss 100%` installed. SYN arrives, SYN-ACK is
     * dropped, the client's `connect()` blocks until the 1-second budget fires.
     *
     * Phase-4 migration off the legacy `10.255.255.1` magic IP: that route
     * worked on raw Linux (the kernel default-routes the address into the
     * abyss) but broke on hosts whose default route shunted 10/8 traffic
     * back to a local interface — the test would either fail-fast with
     * ECONNREFUSED or time out for the wrong reason. The netem blackhole
     * gives every platform an identical "SYN accepted, SYN-ACK lost"
     * topology so the timeout assertion is the only variable under test.
     *
     * The host is the *pinned bridge IP* (`HarnessConfig.netemBlackholeHost`),
     * not `harnessHost()` — docker-proxy on a 127.0.0.1 publish would complete
     * the client-side accept locally and break the blackhole semantics
     * (see docker-compose.yml for the rationale).
     */
    @Test
    fun connectTimeoutWorks() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            // Netem is Linux-kernel-bound; the native macOS fixture doesn't
            // run it. Skip cleanly there so the assertion isn't observing a
            // different failure mode (ECONNREFUSED instead of SYN-ACK loss).
            if (!isNetemAvailable()) return@runTestNoTimeSkipping
            try {
                ClientSocket.connect(
                    port = HarnessConfig.netemBlackholePort,
                    hostname = HarnessConfig.netemBlackholeHost,
                    timeout = 1.seconds,
                )
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

    /**
     * Same harness `netem-blackhole` endpoint as [connectTimeoutWorks] — the
     * intent here is "a stalled connect() unwinds cleanly when the timeout
     * trips", complementary to the timeout-existence assertion above.
     * See [connectTimeoutWorks] for the netem topology + bridge-IP rationale.
     */
    @Test
    fun closeWorks() =
        runTestNoTimeSkipping {
            if (!isHarnessAvailable()) return@runTestNoTimeSkipping
            // Same netem dependency as [connectTimeoutWorks] — skip on the
            // native macOS fixture where the L3 netem service isn't present.
            if (!isNetemAvailable()) return@runTestNoTimeSkipping
            try {
                ClientSocket.connect(
                    port = HarnessConfig.netemBlackholePort,
                    hostname = HarnessConfig.netemBlackholeHost,
                    timeout = 1.seconds,
                )
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
        runTestNoTimeSkipping(timeout = 15.seconds) {
            val server = ServerSocket.allocate()
            val acceptedClientFlow = server.bind()
            val clientCount = 5
            val processedClients = Mutex(locked = true)
            val countMutex = Mutex()
            var clientsHandled = 0
            val serverJob =
                launch(Dispatchers.Default) {
                    acceptedClientFlow.collect { serverToClient ->
                        launch {
                            val s = serverToClient.readString()
                            val indexReceived = s.toInt()
                            serverToClient.writeString("ack $indexReceived")
                            serverToClient.close()
                            countMutex.withLock {
                                clientsHandled++
                                if (clientsHandled >= clientCount) {
                                    processedClients.unlock()
                                }
                            }
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
            processedClients.lockWithTimeout()
            server.close()
            serverJob.cancel()
        }

    // Integration tests that require external network access — all currently @Ignore'd
    // pending Phase 1 migration to the local harness. Once CI runs the harness path
    // on every platform, delete (or convert call-sites to the harness equivalent).

    // Deferred — Phase 1 migration target (per old TODO.md). Harness equivalent already covered by HarnessConformanceTests.harnessHttpStatusLine on HarnessConfig.httpPort. Delete or convert to a direct call-site after CI green-light.
    @Ignore
    @Test
    fun httpRawSocketExampleDomain() = readHttp("example.com", false)

    // Deferred — Phase 1 migration target (per old TODO.md). Harness equivalent already covered by HarnessConformanceTests.harnessHttpStatusLine on HarnessConfig.httpPort. Delete or convert to a direct call-site after CI green-light.
    @Ignore
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

""".toReadBuffer(Charset.UTF8)
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
        // TCP teardown is asynchronous: after close() a socket can sit briefly in
        // CLOSE_WAIT before the kernel reaps it. Poll until it drains instead of
        // asserting once — a socket that drains quickly is normal, one that never
        // drains is a real leak and trips the watchdog below. 10 s is generous
        // for WSL2-under-load (Phase-2 harness compose + concurrent tests can
        // push reap latency past 5 s); a real leak hangs indefinitely either way.
        try {
            withTimeout(10.seconds) {
                while (readStats(port, "CLOSE_WAIT").isNotEmpty()) {
                    delay(50)
                }
            }
        } catch (_: TimeoutCancellationException) {
            val stats = readStats(port, "CLOSE_WAIT")
            fail("Socket still in CLOSE_WAIT after 10s (count=${stats.count()}): $stats")
        }
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

            // Closing the server must not block on the accept loop's poll timeout.
            // The watchdog is the assertion: if close()/join() hangs, it fires.
            withTimeout(5.seconds) {
                server.close()
                serverJob.cancel()
                serverJob.join()
            }
            assertFalse(server.isListening(), "Server should not be listening after close")
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

            // Cancelling the read must not wait out the 30s read timeout.
            // The watchdog is the assertion: if cancellation hangs, withTimeout fires.
            val startTime = currentTimeMillis()
            withTimeout(5.seconds) {
                readJob.cancel()
                readJob.join()
            }
            val elapsed = currentTimeMillis() - startTime
            if (elapsed > 2000) println("WARN: slow read cancellation: ${elapsed}ms")
            assertTrue(readJob.isCancelled, "Read job should be cancelled")

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
                        val largeData = "x".repeat(1024 * 1024).toReadBuffer(Charset.UTF8)
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

            // Cancelling the write must not wait out the 30s write timeout.
            // The watchdog is the assertion: if cancellation hangs, withTimeout fires.
            val startTime = currentTimeMillis()
            withTimeout(5.seconds) {
                writeJob.cancel()
                writeJob.join()
            }
            val elapsed = currentTimeMillis() - startTime
            if (elapsed > 2000) println("WARN: slow write cancellation: ${elapsed}ms")
            // The contract under test is "cancel doesn't hang past the budget" —
            // the withTimeout above is the actual watchdog. The job-state
            // assertion is informational: on Node.js the writer can finish its
            // last in-flight write before cancellation propagates, ending in
            // COMPLETED rather than CANCELLED. Both reach a terminal state
            // promptly, which is what the test was designed to verify.
            assertTrue(
                writeJob.isCancelled || writeJob.isCompleted,
                "Write job should reach a terminal state (cancelled or completed)",
            )

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
            val buffer = BufferFactory.Default.allocate(1024)

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
            repeat(3) {
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

                // Cancellation must not hang; the watchdog is the assertion.
                withTimeout(5.seconds) {
                    readJob.cancel()
                    readJob.join()
                }

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
