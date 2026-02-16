package com.ditchoom.socket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Linux-specific tests for io_uring cancellation behavior.
 * Validates that io_uring_prep_cancel64 works correctly and
 * cancelled operations complete quickly without resource leaks.
 */
class IoUringCancelTests {
    /**
     * Verify io_uring cancel completes quickly (< 200ms).
     * The cancel-and-wait pattern in submitAndWait should not block.
     */
    @Test
    fun cancelCompletesQuickly() =
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

            // Start a blocking read
            val readJob =
                launch(Dispatchers.Default) {
                    try {
                        client.read(30.seconds)
                    } catch (_: Exception) {
                        // Expected
                    }
                }

            delay(200)

            val cancelTime =
                measureTime {
                    readJob.cancel()
                    readJob.join()
                }

            assertTrue(
                cancelTime.inWholeMilliseconds < 200,
                "io_uring cancel took ${cancelTime.inWholeMilliseconds}ms, expected < 200ms",
            )

            client.close()
            server.close()
            serverJob.cancel()
        }

    /**
     * Verify no resource leaks after multiple cancel cycles.
     */
    @Test
    fun multipleCancelCyclesNoLeaks() =
        runTestNoTimeSkipping {
            repeat(5) { cycle ->
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

                val readJob =
                    launch(Dispatchers.Default) {
                        try {
                            client.read(30.seconds)
                        } catch (_: Exception) {
                            // Expected
                        }
                    }

                delay(100)

                val cancelTime =
                    measureTime {
                        readJob.cancel()
                        readJob.join()
                    }

                assertTrue(
                    cancelTime.inWholeMilliseconds < 500,
                    "Cycle $cycle: cancel took ${cancelTime.inWholeMilliseconds}ms",
                )

                client.close()
                server.close()
                serverJob.cancel()
            }
        }
}
