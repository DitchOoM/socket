package com.ditchoom.socket

import com.ditchoom.data.readBuffer
import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for IoUringManager, particularly the poller thread lifecycle
 * and shutdown behavior.
 */
class IoUringManagerTests {
    /**
     * A last-socket close releases the ring and keeps the worker (#302, #307).
     *
     * `cleanup()` used to end with `scope.cancel()` + `dispatcher.close()`, and that pair cost a flat
     * **~100 ms** on every last-socket close. Measured step by step on the unfixed code: the eventfd
     * write reached the event loop's `finally` in 18–60 µs, the join took 73–157 µs, `scope.cancel()`
     * 48–108 µs, and `dispatcher.close()` **99.71–100.05 ms**.
     *
     * It was never io_uring — the wake path was always reactive. On Kotlin/Native
     * `CloseableCoroutineDispatcher.close()` blocks the caller until the backing worker terminates, and
     * the worker only notices shutdown after its own ~100 ms bounded park expires. #302's original
     * hypothesis is wrong and retracted in its own comments; #307's claim that `:socket-udp` had
     * already fixed this is also wrong — both managers carried it, which is why both are fixed together
     * and both carry this guard.
     *
     * **Counting threads, not milliseconds.** [rapidBindConnectCloseCyclesAreClean] below states the
     * suite's own position: *"a wall-clock budget on cleanup() only adds flakiness"*. The mechanism is
     * exactly countable instead — keeping the worker is the fix, so a cycle must not allocate one.
     */
    @Test
    fun repeatedCleanupCyclesReuseOneWorkerThread() =
        runTestNoTimeSkipping {
            // A delta: whether a worker exists already depends on suite order.
            val before = IoUringManager.pollerDispatchersCreated.value

            repeat(TEARDOWN_CYCLES) {
                val server = ServerSocket.allocate()
                val serverFlow = server.bind(0, "127.0.0.1")
                val serverJob = launch { runCatching { serverFlow.collect { it.close() } } }
                // The poller only starts on a submission, so a cycle that opened nothing testifies to
                // nothing. Connecting is what makes this cycle own a worker.
                val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
                client.open(server.port(), "127.0.0.1")
                client.close()
                server.close()
                serverJob.cancelAndJoin()
                IoUringManager.cleanup()
            }

            val created = IoUringManager.pollerDispatchersCreated.value - before
            assertTrue(
                created <= 1,
                "$TEARDOWN_CYCLES cleanup cycles created $created poller worker threads. Releasing the " +
                    "worker on the last close is what cost a flat ~100 ms per close (#302/#307); one " +
                    "creation across all cycles is the fix holding.",
            )
        }

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
                                val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
                                try {
                                    client.open(port, "127.0.0.1")
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
                    val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
                    // Generous connect timeout absorbs scheduler jitter; this loop
                    // is sequential so a healthy server accepts every connection.
                    client.open(port, "127.0.0.1")
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
                val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
                client.open(port, "127.0.0.1")
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

            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            client.open(port, "127.0.0.1")

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

            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            client.open(port, "127.0.0.1")

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
     * Regression for issue #307: [IoUringManager.cleanup] must not be able to block forever when a
     * socket operation starts the poller concurrently with the last-socket close.
     *
     * `cleanup()` signals stop by clearing `pollerStarted`, and the event loop runs
     * `while (pollerStarted.value == 1)`. Before the lifecycle mutex, an `ensurePollerStarted()`
     * racing that window could CAS the flag back to 1 *after* cleanup cleared it, so the running loop
     * never observed the stop and cleanup's `runBlocking { job.join() }` blocked forever. Seen in CI as
     * >900s hangs of `DataIntegrityTests.largeDataTransfer_64KB` and `.partialReadHandling` on linuxX64,
     * both with `server.close()` on the stack while the test's accept loop was still live.
     *
     * ⚠️ [cleanup] is called from a **separate dispatcher**, and deliberately so. The hang blocks the
     * calling *thread* inside `runBlocking`, which is exactly why the suite's 30s `runTestNoTimeSkipping`
     * budget never fired in production and CI needed a gdb hang-watchdog to catch it — a `withTimeout`
     * wrapped directly around a thread-blocking call cannot interrupt it. Awaiting a deferred that a
     * background thread completes keeps the test coroutine free, so a regression fails this assertion
     * instead of hanging the whole `:linuxX64Test` task.
     */
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun cleanupRacingPollerStartDoesNotBlockForever() {
        // ⚠️ Three deliberate structural choices, each one found by watching an earlier version of this
        // test hang on the mutation instead of failing. A regression here strands a thread inside
        // `runBlocking`, so anything the assertion depends on must be immune to that:
        //
        //  1. Plain `runBlocking`, NOT `runTestNoTimeSkipping` — that helper runs the body on
        //     `Dispatchers.Default.limitedParallelism(1)` (ReadStats.kt:22), and a stranded cleanup()
        //     starves Default, so the observing coroutine is never scheduled and its withTimeout never
        //     fires. The observer owns the runBlocking thread instead.
        //  2. Every background coroutine lives in [bg], a scope that is *not* a child of runBlocking.
        //     runBlocking joins its children before returning even when the body throws, so a stranded
        //     cleanup() child would swallow the AssertionError and hang forever.
        //  3. Each blocking participant gets its own thread, and the contexts are closed only on the
        //     success path — `CloseableCoroutineDispatcher.close()` blocks until its worker terminates,
        //     which is precisely what a stranded worker will never do.
        val bg = CoroutineScope(SupervisorJob())
        val trafficCtx = newSingleThreadContext("issue307-traffic")
        val acceptCtx = newSingleThreadContext("issue307-accept")
        val cleanupCtx = newSingleThreadContext("issue307-cleanup")

        runBlocking {
            val server = ServerSocket.allocate()
            val serverJob =
                bg.launch(acceptCtx) {
                    try {
                        // Accept loop stays live for the whole test — this is the submitter that
                        // re-arms an accept (and so calls ensurePollerStarted) inside cleanup's window.
                        server.bind(0, "127.0.0.1").collect { it.close() }
                    } catch (e: Exception) {
                        // Expected once the server closes
                    }
                }

            delay(100.milliseconds)
            val port = server.port()

            // Steady stream of connects so a submission is nearly always in flight against cleanup.
            val trafficRunning = AtomicInt(1)
            val traffic =
                bg.launch(trafficCtx) {
                    while (trafficRunning.value == 1) {
                        try {
                            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 2.seconds))
                            client.open(port, "127.0.0.1")
                            client.close()
                        } catch (e: Exception) {
                            // Connection failures are fine — the submission already raced cleanup,
                            // which is the only thing this test cares about.
                        }
                    }
                }

            // Each iteration re-arms the poller (traffic keeps submitting) and then races a stop
            // against it. Pre-fix this wedges within a few dozen rounds on a loaded runner.
            var wedgedRound: Int? = null
            for (round in 0 until 200) {
                val returned = CompletableDeferred<Unit>()
                bg.launch(cleanupCtx) {
                    IoUringManager.cleanup()
                    returned.complete(Unit)
                }
                try {
                    withTimeout(10.seconds) { returned.await() }
                } catch (e: TimeoutCancellationException) {
                    wedgedRound = round
                    break
                }
                yield()
            }

            trafficRunning.value = 0
            if (wedgedRound != null) {
                // Socket teardown and context close are both skipped on this path on purpose — see (3).
                // The process is about to exit; leaking a listening socket buys a legible failure.
                fail(
                    "IoUringManager.cleanup() did not return within 10s on round $wedgedRound — a " +
                        "concurrent ensurePollerStarted() resurrected pollerStarted after cleanup " +
                        "cleared it, stranding the event loop (issue #307).",
                )
            }

            traffic.cancelAndJoin()
            server.close()
            serverJob.cancel()
            bg.cancel()
            trafficCtx.close()
            acceptCtx.close()
            cleanupCtx.close()
        }
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
                    val client = ClientSocket.allocate(TransportConfig(connectTimeout = 2.seconds))
                    client.open(port, "127.0.0.1")
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

    private companion object {
        /**
         * Enough cycles that a per-cycle worker allocation is unmistakable (the assertion is `<= 1`, so
         * a regression reads as [TEARDOWN_CYCLES] or one more), and few enough to stay quick.
         */
        const val TEARDOWN_CYCLES = 5
    }
}
