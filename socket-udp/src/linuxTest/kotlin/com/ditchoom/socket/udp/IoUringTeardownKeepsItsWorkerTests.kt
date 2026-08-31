package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A last-socket close releases the ring and keeps the worker (#302, #307).
 *
 * `IoUringManager.cleanup()` used to end with `scope.cancel()` + `dispatcher.close()`, and that pair
 * cost a flat **~100 ms** on every last-socket close. Measured step by step on the unfixed code:
 *
 * | step | cost |
 * |---|---|
 * | eventfd write → the event loop's `finally` is reached | 18–60 µs |
 * | `runBlocking { job.join() }` | 73–157 µs |
 * | `scope.cancel()` | 48–108 µs |
 * | **`dispatcher.close()`** | **99.71–100.05 ms** |
 *
 * It was never io_uring — the wake path was always reactive. On Kotlin/Native
 * `CloseableCoroutineDispatcher.close()` blocks the caller until the backing worker terminates, and
 * the worker only notices shutdown after its own ~100 ms bounded park expires. #302's original
 * hypothesis (a poll timeout the eventfd failed to cut short) was wrong and is retracted in its own
 * comments; the strace it rested on shows the coroutine worker being torn down *after* the ring was
 * already gone, which is why no `io_uring_enter` appears in the window.
 *
 * **Why this test counts threads instead of milliseconds.** The sibling suite in the root module
 * already decided against wall-clock budgets on `cleanup()` — "a wall-clock budget on `cleanup()` only
 * adds flakiness" — and it is right. The mechanism is exactly countable instead: keeping the worker is
 * the fix, so a bind/close/cleanup cycle must not allocate one. Re-adding `dispatcher?.close()` (with
 * its `getAndSet(null)`) makes [IoUringManager.pollerDispatchersCreated] grow once per cycle, which is
 * an equality, not a budget.
 *
 * The second assertion covers the one mutation the counter alone would miss — closing the dispatcher
 * without nulling the ref, which would keep the count at 1 and leave every later cycle with a dead
 * worker. Each cycle does real I/O, so a dead worker cannot pass it.
 */
@OptIn(ExperimentalDatagramApi::class)
class IoUringTeardownKeepsItsWorkerTests {
    @Test
    fun repeatedLastSocketClosesReuseOneWorkerThread() =
        runBlocking {
            // Read as a delta: whether a worker already exists when this test runs depends on suite
            // order, so cycle 0 may or may not own the one legitimate creation.
            val before = IoUringManager.pollerDispatchersCreated.value

            repeat(CYCLES) { cycle ->
                val receiver = UdpSocket.bind("127.0.0.1", 0)
                val sender = UdpSocket.bind("127.0.0.1", 0)
                try {
                    val text = "cycle-$cycle"
                    sender.send(payload(text), to = receiver.localAddress)
                    val received = withTimeout(RECEIVE_TIMEOUT_MILLIS) { receiver.receive() }
                    val datagram = assertIs<DatagramReadResult.Received>(received).datagram
                    assertEquals(
                        text,
                        datagram.payload.readByteArray(datagram.payload.remaining()).decodeToString(),
                        "cycle $cycle did no real I/O, so it cannot testify about the worker that served it",
                    )
                } finally {
                    sender.close()
                    receiver.close()
                    // Explicit, and load-bearing: the teardown this test is about only runs when the
                    // *last* socket closes, and whether these were the last two depends on whether any
                    // other suite leaked one. Left implicit, a leak elsewhere would mean cleanup never
                    // ran, no worker was ever released, and this test passed by not exercising anything
                    // — a proof that cannot lose. Calling it is also what the root module's sibling
                    // suite does, for the same reason.
                    IoUringManager.cleanup()
                }
            }

            val created = IoUringManager.pollerDispatchersCreated.value - before
            assertTrue(
                created <= 1,
                "$CYCLES bind/close cycles created $created poller worker threads. Releasing the worker " +
                    "on the last close is what cost a flat ~100 ms per close (#302/#307); one creation " +
                    "across all cycles is the fix holding.",
            )
        }

    private fun payload(text: String): PlatformBuffer {
        val bytes = text.encodeToByteArray()
        return BufferFactory.deterministic().allocate(bytes.size).apply {
            writeBytes(bytes)
            resetForRead()
        }
    }

    private companion object {
        const val RECEIVE_TIMEOUT_MILLIS = 5_000L

        /**
         * Enough cycles that a per-cycle creation is unmistakable (the assertion is `<= 1`, so any
         * regression shows as [CYCLES] or [CYCLES] + 1), and few enough to stay well under a second.
         */
        const val CYCLES = 8
    }
}
