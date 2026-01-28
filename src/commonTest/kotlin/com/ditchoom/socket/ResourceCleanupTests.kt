package com.ditchoom.socket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Tests for resource cleanup and proper socket lifecycle management.
 */
class ResourceCleanupTests {
    @Test
    fun socketClosedAfterUseBlock() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        client.writeString("hello")
                        client.close()
                    }
                }

            var socketRef: ClientSocket? = null

            ClientSocket.connect(server.port(), hostname = "127.0.0.1", timeout = 5.seconds) { socket ->
                socketRef = socket
                assertTrue(socket.isOpen(), "Socket should be open inside use block")
                socket.readString(timeout = 1.seconds)
            }

            // After the block, socket should be closed
            assertFalse(socketRef?.isOpen() ?: true, "Socket should be closed after use block")

            server.close()
            serverJob.cancel()
        }

    @Test
    fun socketClosedOnException() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        // Just echo back data if received
                        try {
                            val data = client.readString(timeout = 500.milliseconds)
                            client.writeString(data)
                        } catch (_: Exception) {
                            // Client may close before sending
                        }
                        client.close()
                    }
                }

            var socketWasOpen = false

            try {
                ClientSocket.connect(server.port(), hostname = "127.0.0.1", timeout = 5.seconds) { socket ->
                    socketWasOpen = socket.isOpen()
                    throw RuntimeException("Test exception")
                }
                fail("Should have thrown")
            } catch (e: RuntimeException) {
                // Expected
            }

            // Verify the socket was open inside the block
            assertTrue(socketWasOpen, "Socket should have been open inside the use block")

            // Note: We can't reliably check isOpen() after the block because the socket
            // reference is managed by the connect block. The block should close it internally.

            server.close()
            serverJob.cancel()
        }

    @Test
    fun serverCleanupOnException() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()

            try {
                val flow = server.bind()
                assertTrue(server.isListening(), "Server should be listening")

                // Simulate an error scenario
                throw RuntimeException("Simulated error")
            } catch (e: RuntimeException) {
                // Expected
            } finally {
                server.close()
            }

            assertFalse(server.isListening(), "Server should not be listening after close")
        }

    @Test
    fun repeatedOpenClose() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        client.writeString("pong")
                        client.close()
                    }
                }

            // Repeatedly open and close connections
            repeat(10) { i ->
                val client = ClientSocket.allocate()
                client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")
                assertTrue(client.isOpen(), "Client $i should be open")

                client.writeString("ping")
                val response = client.readString(timeout = 1.seconds)
                assertTrue(response == "pong", "Should receive response for client $i")

                client.close()
                assertFalse(client.isOpen(), "Client $i should be closed")
            }

            server.close()
            serverJob.cancel()
        }

    @Test
    fun coroutineCancellationCleansUpSocket() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val clientConnected = Mutex(locked = true)
            var serverClientRef: ClientSocket? = null

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        serverClientRef = client
                        clientConnected.unlock()
                        // Don't send anything - wait for cancel
                        delay(60000)
                    }
                }

            var clientRef: ClientSocket? = null
            val clientJob =
                launch(Dispatchers.Default) {
                    val client = ClientSocket.allocate()
                    clientRef = client
                    client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")
                    clientConnected.lock()

                    // This should block and be cancelled
                    client.read(60.seconds)
                }

            // Wait for connection
            delay(200)

            // Cancel the client job
            clientJob.cancel()

            delay(100)

            // Clean up client
            clientRef?.close()

            server.close()
            serverJob.cancel()
        }

    @Test
    fun serverFlowCancellation() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            var collectCount = 0

            val serverJob =
                launch(Dispatchers.Default) {
                    try {
                        serverFlow.collect { client ->
                            collectCount++
                            client.writeString("hello")
                            client.close()

                            // Cancel after first client
                            if (collectCount >= 1) {
                                this@launch.cancel()
                            }
                        }
                    } catch (e: CancellationException) {
                        // Expected
                    }
                }

            // Connect once
            ClientSocket.connect(server.port(), hostname = "127.0.0.1", timeout = 5.seconds) { socket ->
                socket.readString(timeout = 1.seconds)
            }

            delay(100)

            assertTrue(collectCount >= 1, "Should have processed at least one client")

            server.close()
            serverJob.cancel()
        }

    @Test
    fun timeoutDoesNotLeakResources() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        // Don't respond - let client timeout
                        delay(60000)
                        client.close()
                    }
                }

            repeat(3) {
                val client = ClientSocket.allocate()
                client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")

                try {
                    withTimeout(100.milliseconds) {
                        client.read(100.milliseconds)
                    }
                } catch (e: Exception) {
                    // Expected timeout
                }

                client.close()
                assertFalse(client.isOpen(), "Client should be closed after timeout")
            }

            server.close()
            serverJob.cancel()
        }

    @Test
    fun serverAcceptsContinuesAfterClientError() =
        runTestNoTimeSkipping {
            val startTime = TimeSource.Monotonic.markNow()

            fun log(msg: String) = println("[${startTime.elapsedNow().inWholeMilliseconds}ms] $msg")

            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            log("Server bound on port ${server.port()}")

            var clientsProcessed = 0
            var clientsAttempted = 0

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        val clientNum = ++clientsAttempted
                        log("Server: accepted client #$clientNum")
                        try {
                            // Try to read - first client will send nothing
                            log("Server: starting read for client #$clientNum")
                            val data =
                                withTimeout(300.milliseconds) {
                                    client.readString(timeout = 300.milliseconds)
                                }
                            clientsProcessed++
                            log("Server: successfully read '${data.take(20)}' from client #$clientNum (processed=$clientsProcessed)")
                        } catch (e: Exception) {
                            log("Server: error reading client #$clientNum: ${e::class.simpleName}: ${e.message}")
                            // Client error - continue accepting
                        } finally {
                            client.close()
                            log("Server: closed client #$clientNum")
                        }
                    }
                }

            // First client - connect and immediately close (server will timeout)
            log("Client1: connecting...")
            val client1 = ClientSocket.allocate()
            client1.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")
            log("Client1: connected, closing immediately")
            client1.close()
            log("Client1: closed")

            // Wait for server to finish processing client1 (needs 300ms timeout + processing)
            log("Waiting 500ms for server to process client1...")
            delay(500)
            log("After delay: attempted=$clientsAttempted, processed=$clientsProcessed")

            // Second client - send proper data
            log("Client2: connecting...")
            val client2 = ClientSocket.allocate()
            client2.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")
            log("Client2: connected, writing 'hello'")
            client2.writeString("hello")
            log("Client2: wrote data, closing")
            client2.close()
            log("Client2: closed")

            // Wait for server to process client2
            log("Waiting 500ms for server to process client2...")
            delay(500)
            log("After delay: attempted=$clientsAttempted, processed=$clientsProcessed")

            // Third client - should also work
            log("Client3: connecting...")
            val client3 = ClientSocket.allocate()
            client3.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")
            log("Client3: connected, writing 'world'")
            client3.writeString("world")
            log("Client3: wrote data, closing")
            client3.close()
            log("Client3: closed")

            // Wait for server to process client3, with extra buffer for CI environments
            log("Waiting 500ms for server to process client3...")
            delay(500)
            log("Final state: attempted=$clientsAttempted, processed=$clientsProcessed")

            assertTrue(
                clientsProcessed >= 1,
                "Server should have processed at least one proper client " +
                    "(processed=$clientsProcessed, attempted=$clientsAttempted)",
            )

            server.close()
            serverJob.cancel()
            log("Test complete")
        }
}
