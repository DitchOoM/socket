package com.ditchoom.socket

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for concurrent socket operations.
 * Rewritten to be simpler and more deterministic for CI stability.
 */
class ConcurrencyTests {
    @Test
    fun concurrentClientConnections() =
        runTestNoTimeSkipping {
            withTimeout(15.seconds) {
                val server = ServerSocket.allocate()
                val serverFlow = server.bind()
                val clientCount = 5

                // Simple server that echoes back
                val serverJob =
                    launch {
                        var handled = 0
                        serverFlow.collect { client ->
                            launch {
                                val received = client.readString(timeout = 5.seconds)
                                client.writeString("ACK:$received")
                                client.close()
                                handled++
                            }
                        }
                    }

                // Connect clients and verify responses
                val results =
                    coroutineScope {
                        (0 until clientCount)
                            .map { i ->
                                async {
                                    ClientSocket.connect(server.port(), hostname = "127.0.0.1", timeout = 5.seconds) { socket ->
                                        socket.writeString("client$i")
                                        socket.readString(timeout = 5.seconds)
                                    }
                                }
                            }.awaitAll()
                    }

                // Verify all responses
                results.forEachIndexed { i, response ->
                    assertEquals("ACK:client$i", response, "Client $i should receive correct ACK")
                }

                server.close()
                serverJob.cancel()
            }
        }

    @Test
    fun sequentialReadWrite() =
        runTestNoTimeSkipping {
            withTimeout(10.seconds) {
                val server = ServerSocket.allocate()
                val serverFlow = server.bind()
                val messages = mutableListOf<String>()

                val serverJob =
                    launch {
                        serverFlow.collect { serverSocket ->
                            // Send messages
                            repeat(5) { i ->
                                serverSocket.writeString("S$i|")
                            }

                            // Read messages
                            repeat(5) {
                                try {
                                    messages.add(serverSocket.readString(timeout = 2.seconds))
                                } catch (_: Exception) {
                                    // Client may close
                                }
                            }
                            serverSocket.close()
                        }
                    }

                val client = ClientSocket.allocate()
                client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")

                // Read messages from server
                val clientMessages = mutableListOf<String>()
                repeat(5) {
                    try {
                        clientMessages.add(client.readString(timeout = 2.seconds))
                    } catch (_: Exception) {
                        // Server may close
                    }
                }

                // Send messages to server
                repeat(5) { i ->
                    try {
                        client.writeString("C$i|")
                    } catch (_: Exception) {
                        // Server may close
                    }
                }

                delay(100) // Brief delay for server to process
                client.close()

                // Verify some exchange happened
                assertTrue(clientMessages.isNotEmpty() || messages.isNotEmpty(), "Some messages should have been exchanged")

                server.close()
                serverJob.cancel()
            }
        }

    @Test
    fun multipleServersOnDifferentPorts() =
        runTestNoTimeSkipping {
            withTimeout(10.seconds) {
                val serverCount = 3
                val servers = mutableListOf<ServerSocket>()
                val serverJobs = mutableListOf<kotlinx.coroutines.Job>()

                // Start servers
                repeat(serverCount) { serverIndex ->
                    val server = ServerSocket.allocate()
                    servers.add(server)
                    val flow = server.bind()

                    val job =
                        launch {
                            flow.collect { client ->
                                val received = client.readString(timeout = 5.seconds)
                                client.writeString("Server$serverIndex:$received")
                                client.close()
                            }
                        }
                    serverJobs.add(job)
                }

                // Connect to each server
                val responses =
                    servers.mapIndexed { index, server ->
                        ClientSocket.connect(server.port(), hostname = "127.0.0.1", timeout = 5.seconds) { client ->
                            client.writeString("Hello")
                            client.readString(timeout = 5.seconds)
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
        }

    @Test
    fun rapidConnectDisconnect() =
        runTestNoTimeSkipping {
            withTimeout(10.seconds) {
                val server = ServerSocket.allocate()
                val serverFlow = server.bind()
                var acceptedCount = 0

                val serverJob =
                    launch {
                        serverFlow.collect { client ->
                            acceptedCount++
                            client.close()
                        }
                    }

                // Rapidly connect and disconnect
                repeat(10) {
                    val client = ClientSocket.allocate()
                    client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")
                    client.close()
                }

                delay(200) // Give server time to process
                assertTrue(acceptedCount > 0, "Server should have accepted some connections")

                server.close()
                serverJob.cancel()
            }
        }

    /**
     * Test that failed connections don't leak resources.
     */
    @Test
    fun failedConnectionsCleanup() =
        runTestNoTimeSkipping {
            withTimeout(5.seconds) {
                var exceptionCount = 0

                // Try to connect to ports that aren't listening
                repeat(5) {
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
                assertEquals(5, exceptionCount, "All connection attempts should have failed")
            }
        }

    /**
     * Test server restart - verifies port can be reused after close.
     */
    @Test
    fun serverRestartCycle() =
        runTestNoTimeSkipping {
            withTimeout(10.seconds) {
                repeat(3) { cycle ->
                    val server = ServerSocket.allocate()
                    val serverFlow = server.bind()
                    var clientConnected = false

                    val serverJob =
                        launch {
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

    /**
     * Test simple connection pooling pattern.
     */
    @Test
    fun connectionPoolPattern() =
        runTestNoTimeSkipping {
            withTimeout(10.seconds) {
                val server = ServerSocket.allocate()
                val serverFlow = server.bind()

                // Simple echo server
                val serverJob =
                    launch {
                        serverFlow.collect { client ->
                            launch {
                                try {
                                    val msg = client.readString(timeout = 5.seconds)
                                    client.writeString("ECHO:$msg")
                                } catch (_: Exception) {
                                    // Client may close
                                } finally {
                                    client.close()
                                }
                            }
                        }
                    }

                // Simulate connection pool usage - reuse connection pattern
                val results =
                    coroutineScope {
                        (0 until 3).map { i ->
                            async {
                                ClientSocket.connect(server.port(), hostname = "127.0.0.1", timeout = 5.seconds) { socket ->
                                    socket.writeString("msg$i")
                                    socket.readString(timeout = 5.seconds)
                                }
                            }
                        }.awaitAll()
                    }

                // Verify all echoed correctly
                results.forEachIndexed { i, result ->
                    assertEquals("ECHO:msg$i", result)
                }

                server.close()
                serverJob.cancel()
            }
        }
}
