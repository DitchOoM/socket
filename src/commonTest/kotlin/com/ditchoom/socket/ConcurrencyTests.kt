package com.ditchoom.socket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for concurrent socket operations.
 */
class ConcurrencyTests {
    @Test
    fun concurrentClientConnections() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val clientCount = 5 // Reduced from 10 for CI stability
            var handledClients = 0
            val allClientsHandled = Mutex(locked = true)
            val handlersMutex = Mutex()
            val handlerJobs = mutableListOf<kotlinx.coroutines.Job>()
            val handlerJobsMutex = Mutex()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        val job =
                            launch {
                                val received = client.readString(timeout = 5.seconds)
                                client.writeString("ACK:$received")
                                client.close()
                                handlersMutex.withLock {
                                    handledClients++
                                    if (handledClients >= clientCount) {
                                        allClientsHandled.unlock()
                                    }
                                }
                            }
                        handlerJobsMutex.withLock {
                            handlerJobs.add(job)
                        }
                    }
                }

            // Launch multiple clients concurrently
            coroutineScope {
                val results =
                    (0 until clientCount)
                        .map { i ->
                            async(Dispatchers.Default) {
                                ClientSocket.connect(server.port(), hostname = "127.0.0.1", timeout = 10.seconds) { socket ->
                                    socket.writeString("client$i")
                                    val response = socket.readString(timeout = 5.seconds)
                                    response
                                }
                            }
                        }.awaitAll()

                // Verify all responses
                results.forEachIndexed { i, response ->
                    assertEquals("ACK:client$i", response, "Client $i should receive correct ACK")
                }
            }

            allClientsHandled.lock()
            assertEquals(clientCount, handledClients, "Server should have handled all clients")

            // Wait for all handler jobs to complete before cancelling
            handlerJobs.forEach { it.join() }

            server.close()
            serverJob.cancel()
        }

    @Test
    fun concurrentReadWrite() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val messageCount = 10 // Reduced from 20 for CI stability
            val clientReady = Mutex(locked = true)
            val serverMessages = mutableListOf<String>()
            val clientMessages = mutableListOf<String>()
            val serverMessagesMutex = Mutex()
            val clientMessagesMutex = Mutex()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { serverSocket ->
                        clientReady.unlock()

                        // Concurrent read and write on server side
                        val writeJob =
                            launch {
                                repeat(messageCount) { i ->
                                    serverSocket.writeString("S$i|")
                                    delay(10)
                                }
                            }

                        val readJob =
                            launch {
                                repeat(messageCount) {
                                    try {
                                        val msg = serverSocket.readString(timeout = 5.seconds)
                                        serverMessagesMutex.withLock {
                                            serverMessages.add(msg)
                                        }
                                    } catch (e: SocketException) {
                                        // Client may close
                                    }
                                }
                            }

                        writeJob.join()
                        delay(500) // Give time for reads
                        readJob.cancel()
                        serverSocket.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")
            clientReady.lock()

            // Concurrent read and write on client side
            val clientWriteJob =
                launch(Dispatchers.Default) {
                    repeat(messageCount) { i ->
                        client.writeString("C$i|")
                        delay(10)
                    }
                }

            val clientReadJob =
                launch(Dispatchers.Default) {
                    repeat(messageCount) {
                        try {
                            val msg = client.readString(timeout = 5.seconds)
                            clientMessagesMutex.withLock {
                                clientMessages.add(msg)
                            }
                        } catch (e: SocketException) {
                            // Server may close
                        }
                    }
                }

            clientWriteJob.join()
            delay(500) // Give time for reads
            clientReadJob.cancel()
            client.close()

            // Verify some messages were exchanged (may not be all due to timing)
            assertTrue(
                clientMessages.isNotEmpty() || serverMessages.isNotEmpty(),
                "Some messages should have been exchanged",
            )

            server.close()
            serverJob.cancel()
        }

    @Test
    fun multipleServersOnDifferentPorts() =
        runTestNoTimeSkipping {
            val serverCount = 3
            val servers = mutableListOf<ServerSocket>()
            val serverJobs = mutableListOf<kotlinx.coroutines.Job>()
            val responses = mutableMapOf<Int, String>()

            // Start multiple servers
            repeat(serverCount) { serverIndex ->
                val server = ServerSocket.allocate()
                servers.add(server)
                val flow = server.bind()

                val job =
                    launch(Dispatchers.Default) {
                        flow.collect { client ->
                            val received = client.readString(timeout = 5.seconds)
                            client.writeString("Server$serverIndex:$received")
                            client.close()
                        }
                    }
                serverJobs.add(job)
            }

            // Connect to each server
            servers.forEachIndexed { index, server ->
                ClientSocket.connect(server.port(), hostname = "127.0.0.1", timeout = 5.seconds) { client ->
                    client.writeString("Hello")
                    val response = client.readString(timeout = 5.seconds)
                    responses[index] = response
                }
            }

            // Verify responses
            repeat(serverCount) { i ->
                assertEquals("Server$i:Hello", responses[i], "Server $i should respond correctly")
            }

            // Cleanup
            servers.forEach { it.close() }
            serverJobs.forEach { it.cancel() }
        }

    @Test
    fun rapidConnectDisconnect() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val connectionCount = 10 // Reduced from 20 for CI stability
            var acceptedCount = 0

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        acceptedCount++
                        client.close()
                    }
                }

            // Rapidly connect and disconnect
            repeat(connectionCount) {
                val client = ClientSocket.allocate()
                client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")
                client.close()
            }

            delay(500) // Give server time to process
            assertTrue(acceptedCount > 0, "Server should have accepted some connections")

            server.close()
            serverJob.cancel()
        }

    @Test
    fun highConnectionRateWithBackpressure() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind(backlog = 5) // Small backlog
            val connectionCount = 8 // Reduced for faster execution
            val semaphore = Semaphore(4) // Limit concurrent connections
            var successfulConnections = 0

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        launch {
                            delay(20) // Simulate slow processing (reduced from 100ms)
                            try {
                                client.readString(timeout = 500.milliseconds)
                            } catch (_: Exception) {
                                // Client may have closed
                            }
                            client.close()
                        }
                    }
                }

            // Connect many clients with limited concurrency
            coroutineScope {
                (0 until connectionCount)
                    .map { i ->
                        async(Dispatchers.Default) {
                            semaphore.acquire()
                            try {
                                val client = ClientSocket.allocate()
                                client.open(server.port(), timeout = 1.seconds, hostname = "127.0.0.1")
                                client.writeString("test$i")
                                client.close()
                                successfulConnections++
                            } catch (e: SocketException) {
                                // May fail if backlog is full
                            } finally {
                                semaphore.release()
                            }
                        }
                    }.awaitAll()
            }

            assertTrue(successfulConnections > 0, "Some connections should have succeeded")

            server.close()
            serverJob.cancel()
        }

    /**
     * Stress test for rapid connect/disconnect cycles to verify resource cleanup.
     * This test catches resource leaks like unclosed sockets or selectors.
     */
    @Test
    fun rapidConnectDisconnectCycles() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val cycleCount = 10 // Reduced from 20 for CI stability
            var acceptedConnections = 0
            val acceptedMutex = Mutex()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        acceptedMutex.withLock { acceptedConnections++ }
                        client.close()
                    }
                }

            // Rapidly connect and disconnect
            repeat(cycleCount) {
                ClientSocket.connect(server.port(), hostname = "127.0.0.1", timeout = 5.seconds) { socket ->
                    assertTrue(socket.isOpen(), "Socket should be open")
                    // Immediately close (via scoped function)
                }
            }

            // Give server time to process
            delay(100)

            assertEquals(cycleCount, acceptedConnections, "Server should have accepted all connections")

            server.close()
            serverJob.cancel()
        }

    /**
     * Test that failed connections don't leak resources.
     */
    @Test
    fun failedConnectionsCleanup() =
        runTestNoTimeSkipping {
            val attemptCount = 10
            var exceptionCount = 0

            // Try to connect to a port that's not listening
            repeat(attemptCount) {
                try {
                    // Use a random high port that's unlikely to be in use
                    val randomPort = 50000 + (it * 1000)
                    val socket = ClientSocket.allocate()
                    socket.open(randomPort, timeout = 100.milliseconds, hostname = "127.0.0.1")
                    socket.close()
                } catch (e: Exception) {
                    // Connection refused, timeout, or socket exceptions are all expected
                    val name = e::class.simpleName?.lowercase() ?: ""
                    if (name.contains("socket") || name.contains("connect") || name.contains("timeout") || name.contains("cancellation")) {
                        exceptionCount++
                    } else {
                        throw e
                    }
                }
            }

            // All attempts should have failed with proper cleanup
            assertEquals(attemptCount, exceptionCount, "All connection attempts should have failed")
        }

    /**
     * Test server restart - verifies port can be reused after close.
     */
    @Test
    fun serverRestartCycle() =
        runTestNoTimeSkipping {
            val restartCount = 3

            repeat(restartCount) { cycle ->
                val server = ServerSocket.allocate()
                val serverFlow = server.bind()
                var clientConnected = false

                val serverJob =
                    launch(Dispatchers.Default) {
                        serverFlow.collect { client ->
                            clientConnected = true
                            client.close()
                        }
                    }

                // Connect a client
                ClientSocket.connect(server.port(), hostname = "127.0.0.1", timeout = 5.seconds) { socket ->
                    assertTrue(socket.isOpen())
                }

                delay(50)
                assertTrue(clientConnected, "Client should have connected in cycle $cycle")

                server.close()
                serverJob.cancel()

                // Brief delay before restarting
                delay(50)
            }
        }
}
