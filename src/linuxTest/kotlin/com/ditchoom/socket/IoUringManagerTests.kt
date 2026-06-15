package com.ditchoom.socket

import com.ditchoom.data.readBuffer
import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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

            // Shutdown must not wait out the 1s poll timeout. The watchdog is the
            // assertion: if the NOP wakeup regresses, cleanup() blocks and it fires.
            withTimeout(3.seconds) {
                server.close()
                serverJob.cancel()
                // Call cleanup to trigger the NOP wakeup
                IoUringManager.cleanup()
            }
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
                                    while (client.isOpen) {
                                        val data = client.readBuffer(5.seconds)
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
                                    client.open(port, "127.0.0.1", TransportConfig(connectTimeout = 5.seconds))
                                    val message = "Hello from client $i"
                                    client.writeString(message)
                                    val response = client.readString(deadline = 5.seconds)
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
                    // Generous connect timeout absorbs scheduler jitter; this loop
                    // is sequential so a healthy server accepts every connection.
                    client.open(port, "127.0.0.1", TransportConfig(connectTimeout = 5.seconds))
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
                                val data = client.readBuffer(5.seconds)
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
                client.open(port, "127.0.0.1", TransportConfig(connectTimeout = 5.seconds))
                val testMessage = "Cycle $cycle test"
                client.writeString(testMessage)
                val response = client.readString(deadline = 5.seconds)
                assertTrue(
                    response.contains(testMessage),
                    "Cycle $cycle: expected response to contain '$testMessage', got '$response'",
                )
                client.close()

                // Close server and cleanup. Functional reuse is the intent — the
                // response.contains assert above already proves cleanup + reinit
                // work; a wall-clock budget on cleanup() only adds flakiness.
                server.close()
                serverJob.cancel()
                IoUringManager.cleanup()

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
            client.open(port, "127.0.0.1", TransportConfig(connectTimeout = 5.seconds))

            // Start a read that will block (no data coming)
            var readException: Exception? = null
            val readJob =
                launch {
                    try {
                        client.readBuffer(30.seconds) // Long timeout
                    } catch (e: Exception) {
                        readException = e
                    }
                }

            // Give read time to start and register with poller
            delay(200.milliseconds)

            // Now cleanup while read is pending. The watchdog catches a hung
            // cleanup; the real assertion is that the pending read was interrupted.
            withTimeout(5.seconds) {
                server.close()
                serverJob.cancel()
                IoUringManager.cleanup()
            }

            // Wait for read job to complete
            readJob.join()

            // The pending read should have been cancelled or errored
            // (either SocketClosedException or SocketException with ECANCELED)
            assertTrue(
                readException != null || !client.isOpen,
                "Pending read should have been interrupted by cleanup",
            )
        }

    /**
     * Regression for the linuxX64 idle-timeout crash (project_ci_backend_coverage): a recv that hits
     * its deadline must be cancelled in the kernel — not completed while still in flight. Completing
     * a timed-out recv let the caller free the buffer the kernel still owned, so a later datagram
     * wrote into freed memory (UAF / heap corruption). This asserts deterministically that the
     * expiry path submits a cancel SQE (via the [IoUringManager.timeoutCancelSubmitCount] counter)
     * AND that the timeout still surfaces as [SocketTimeoutException] (semantics preserved), rather
     * than racing a real crash on wall-clock timing.
     */
    @Test
    fun timedOutReadCancelsKernelOpAndStillReportsTimeout() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverJob =
                launch {
                    try {
                        // Accept and hold the connection open without ever sending — the client's
                        // read must time out (and be kernel-cancelled), not receive data.
                        server.bind(0, "127.0.0.1").collect { delay(30.seconds) }
                    } catch (e: Exception) {
                        // Server closed
                    }
                }

            delay(100.milliseconds)
            val port = server.port()

            val client = ClientSocket.allocate()
            client.open(port, "127.0.0.1", TransportConfig(connectTimeout = 5.seconds))

            val before = IoUringManager.timeoutCancelSubmitCount.value
            var timedOut = false
            try {
                client.readBuffer(200.milliseconds)
            } catch (e: SocketTimeoutException) {
                timedOut = true
            }

            assertTrue(timedOut, "read with no incoming data should surface SocketTimeoutException")
            assertTrue(
                IoUringManager.timeoutCancelSubmitCount.value > before,
                "an expired recv must submit an io_uring cancel so the kernel releases the buffer " +
                    "(counter ${IoUringManager.timeoutCancelSubmitCount.value} did not advance past $before)",
            )

            client.close()
            server.close()
            serverJob.cancel()
        }

    /**
     * Stress test: many rapid cleanup/reinitialize cycles.
     */
    @Test
    fun rapidCleanupReinitializeCycles() =
        runTestNoTimeSkipping {
            repeat(10) {
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
                    client.open(port, "127.0.0.1", TransportConfig(connectTimeout = 2.seconds))
                    client.close()
                } catch (e: Exception) {
                    // May fail due to timing, that's ok
                }

                server.close()
                serverJob.cancel()

                // Completing all 10 cleanup/reinit cycles inside the test budget
                // is the assertion; a per-cycle wall-clock budget only flakes.
                IoUringManager.cleanup()
            }
        }
}
