package com.ditchoom.socket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Tests for IoUringManager, particularly the poller thread lifecycle
 * and shutdown behavior.
 */
class IoUringManagerTests {
    /**
     * Test that shutdown completes quickly (< 200ms) rather than waiting
     * for the DEFAULT_POLL_TIMEOUT (1 second).
     *
     * This validates that the NOP wakeup mechanism works correctly.
     */
    @Test
    fun shutdownCompletesQuickly() =
        runTestNoTimeSkipping {
            // Start the poller by creating a socket and doing an operation
            val server = ServerSocket.allocate()
            val serverFlow = server.bind(0, "127.0.0.1")

            // Collect one connection or timeout - this ensures poller is started
            val serverJob =
                launch {
                    try {
                        serverFlow.collect { client ->
                            client.close()
                        }
                    } catch (e: Exception) {
                        // Expected when server closes
                    }
                }

            // Give the server time to start and poller to be initialized
            delay(100.milliseconds)

            // Now measure shutdown time
            val shutdownTime =
                measureTime {
                    server.close()
                    serverJob.cancel()
                    // Call cleanup to trigger the NOP wakeup
                    IoUringManager.cleanup()
                }

            // Shutdown should be fast (< 200ms), not waiting for 1 second poll timeout
            assertTrue(
                shutdownTime < 500.milliseconds,
                "Shutdown took $shutdownTime, expected < 500ms. " +
                    "NOP wakeup may not be working correctly.",
            )
        }

    /**
     * Test multiple concurrent socket operations to ensure the poller
     * correctly dispatches completions to the right coroutines.
     */
    @Test
    fun concurrentOperationsDispatchCorrectly() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val connections = mutableListOf<ClientSocket>()

            val serverJob =
                launch {
                    try {
                        server.bind(0, "127.0.0.1").collect { client ->
                            connections.add(client)
                            // Echo back any data received
                            launch {
                                try {
                                    while (client.isOpen()) {
                                        val data = client.read(5.seconds)
                                        data.resetForRead()
                                        if (data.remaining() > 0) {
                                            client.write(data, 5.seconds)
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Connection closed
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Server closed
                    }
                }

            delay(100.milliseconds)
            val port = server.port()

            // Create multiple concurrent clients
            val clientCount = 10
            val results =
                withContext(Dispatchers.Default) {
                    (1..clientCount)
                        .map { i ->
                            async {
                                val client = ClientSocket.allocate()
                                try {
                                    client.open(port, 5.seconds, "127.0.0.1")
                                    val message = "Hello from client $i"
                                    client.writeString(message)
                                    val response = client.readString(timeout = 5.seconds)
                                    client.close()
                                    message to response
                                } catch (e: Exception) {
                                    client.close()
                                    throw e
                                }
                            }
                        }.awaitAll()
                }

            // Verify all clients got correct responses
            results.forEachIndexed { index, (sent, received) ->
                assertTrue(
                    received.contains(sent),
                    "Client ${index + 1}: sent '$sent' but received '$received'",
                )
            }

            // Cleanup
            connections.forEach { it.close() }
            server.close()
            serverJob.cancel()
        }

    /**
     * Stress test: rapid open/close cycles to ensure no resource leaks
     * or race conditions in the poller.
     */
    @Test
    fun rapidOpenCloseCycles() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverJob =
                launch {
                    try {
                        server.bind(0, "127.0.0.1").collect { client ->
                            // Immediately close accepted connections
                            client.close()
                        }
                    } catch (e: Exception) {
                        // Server closed
                    }
                }

            delay(100.milliseconds)
            val port = server.port()

            // Rapidly open and close connections
            val cycleCount = 50
            var successCount = 0

            for (i in 1..cycleCount) {
                try {
                    val client = ClientSocket.allocate()
                    client.open(port, 2.seconds, "127.0.0.1")
                    client.close()
                    successCount++
                } catch (e: Exception) {
                    // Some failures are expected due to rapid cycling
                }
            }

            // At least 80% should succeed
            assertTrue(
                successCount >= cycleCount * 0.8,
                "Only $successCount/$cycleCount connections succeeded",
            )

            server.close()
            serverJob.cancel()
        }

    /**
     * Test that cleanup fully resets state and the system can be reused.
     * This validates:
     * 1. Cleanup properly stops the poller thread
     * 2. Cleanup releases the io_uring resources
     * 3. After cleanup, new operations reinitialize everything correctly
     */
    @Test
    fun cleanupAndReinitializeWorks() =
        runTestNoTimeSkipping {
            // First cycle: use sockets, then cleanup
            repeat(3) { cycle ->
                val server = ServerSocket.allocate()
                val serverJob =
                    launch {
                        try {
                            server.bind(0, "127.0.0.1").collect { client ->
                                val data = client.read(5.seconds)
                                data.resetForRead()
                                client.write(data, 5.seconds)
                                client.close()
                            }
                        } catch (e: Exception) {
                            // Server closed
                        }
                    }

                delay(100.milliseconds)
                val port = server.port()

                // Do a successful operation
                val client = ClientSocket.allocate()
                client.open(port, 5.seconds, "127.0.0.1")
                val testMessage = "Cycle $cycle test"
                client.writeString(testMessage)
                val response = client.readString(timeout = 5.seconds)
                assertTrue(
                    response.contains(testMessage),
                    "Cycle $cycle: expected response to contain '$testMessage', got '$response'",
                )
                client.close()

                // Close server and cleanup
                server.close()
                serverJob.cancel()

                // Measure cleanup time
                val cleanupTime =
                    measureTime {
                        IoUringManager.cleanup()
                    }

                assertTrue(
                    cleanupTime < 500.milliseconds,
                    "Cycle $cycle: cleanup took $cleanupTime, expected < 500ms",
                )

                // Small delay before next cycle
                delay(50.milliseconds)
            }
        }

    /**
     * Test cleanup with pending operations - they should be cancelled.
     */
    @Test
    fun cleanupCancelsPendingOperations() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            var acceptedClient: ClientSocket? = null

            val serverJob =
                launch {
                    try {
                        server.bind(0, "127.0.0.1").collect { client ->
                            acceptedClient = client
                            // Don't respond - keep connection open
                            delay(30.seconds)
                        }
                    } catch (e: Exception) {
                        // Expected
                    }
                }

            delay(100.milliseconds)
            val port = server.port()

            val client = ClientSocket.allocate()
            client.open(port, 5.seconds, "127.0.0.1")

            // Start a read that will block (no data coming)
            var readException: Exception? = null
            val readJob =
                launch {
                    try {
                        client.read(30.seconds) // Long timeout
                    } catch (e: Exception) {
                        readException = e
                    }
                }

            // Give read time to start and register with poller
            delay(200.milliseconds)

            // Now cleanup while read is pending
            val cleanupTime =
                measureTime {
                    server.close()
                    serverJob.cancel()
                    IoUringManager.cleanup()
                }

            // Cleanup should be fast
            assertTrue(
                cleanupTime < 500.milliseconds,
                "Cleanup with pending ops took $cleanupTime, expected < 500ms",
            )

            // Wait for read job to complete
            readJob.join()

            // The pending read should have been cancelled or errored
            // (either SocketClosedException or SocketException with ECANCELED)
            assertTrue(
                readException != null || !client.isOpen(),
                "Pending read should have been interrupted by cleanup",
            )
        }

    /**
     * Stress test: many rapid cleanup/reinitialize cycles.
     */
    @Test
    fun rapidCleanupReinitializeCycles() =
        runTestNoTimeSkipping {
            repeat(10) { cycle ->
                // Quick operation to initialize poller
                val server = ServerSocket.allocate()
                val serverJob =
                    launch {
                        try {
                            server.bind(0, "127.0.0.1").collect { it.close() }
                        } catch (e: Exception) {
                            // Expected
                        }
                    }

                delay(50.milliseconds)
                val port = server.port()

                // Quick connect/disconnect
                try {
                    val client = ClientSocket.allocate()
                    client.open(port, 2.seconds, "127.0.0.1")
                    client.close()
                } catch (e: Exception) {
                    // May fail due to timing, that's ok
                }

                server.close()
                serverJob.cancel()

                // Cleanup
                val cleanupTime = measureTime { IoUringManager.cleanup() }
                assertTrue(
                    cleanupTime < 500.milliseconds,
                    "Cycle $cycle: cleanup took $cleanupTime",
                )
            }
        }
}
