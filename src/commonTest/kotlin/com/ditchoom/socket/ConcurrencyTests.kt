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
            val clientCount = 10
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
            val messageCount = 20
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
            val connectionCount = 20
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
}
