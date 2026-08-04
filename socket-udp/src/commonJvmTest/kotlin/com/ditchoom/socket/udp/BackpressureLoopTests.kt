package com.ditchoom.socket.udp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The JVM send path's backpressure loop, driven directly with a stub writer.
 *
 * A non-blocking `DatagramChannel.write`/`send` returns 0 when the output buffer is full, and the bug
 * this loop replaced was discarding that — turning local pressure into an invisible drop. The loop is
 * the most intricate part of the change and a real socket cannot exercise it: on loopback the sender's
 * queue never fills, because packets move straight to the receiver's queue. Provoking it for real
 * would need a rate-limited link in a network namespace — Linux-only, privileged, and a test of the
 * kernel rather than of this code. Stubbing the writer covers every branch in microseconds instead,
 * on both JVM and Android.
 */
class BackpressureLoopTests {
    private fun view(size: Int = 64): ByteBuffer = ByteBuffer.allocate(size)

    @Test
    fun acceptedImmediately_writesOnce() =
        runBlocking {
            var calls = 0
            val buf = view()
            writeAbsorbingBackpressure(buf, {
                calls++
                it.remaining()
            })
            assertEquals(1, calls, "an accepted datagram must not be retried")
        }

    @Test
    fun refusedThenAccepted_retriesRatherThanDropping() =
        runBlocking {
            var calls = 0
            val buf = view()
            writeAbsorbingBackpressure(buf, { if (++calls < 3) 0 else it.remaining() })
            assertEquals(3, calls, "a zero return is backpressure — it must be retried, not treated as sent")
        }

    @Test
    fun refusedThroughout_reportsWouldBlockRatherThanSucceeding(): Unit =
        runBlocking {
            val buf = view()
            val thrown =
                assertFailsWith<DatagramSendException> {
                    withTimeout(5_000) { writeAbsorbingBackpressure(buf, { 0 }, budget = 5.milliseconds) }
                }
            assertIs<DatagramSendError.WouldBlock>(
                thrown.error,
                "giving up on backpressure must report WouldBlock, not silently return",
            )
        }

    @Test
    fun transportFailure_isReportedTyped(): Unit =
        runBlocking {
            val buf = view()
            val boom = IOException("nope")
            val thrown = assertFailsWith<DatagramSendException> { writeAbsorbingBackpressure(buf, { throw boom }) }
            val error = assertIs<DatagramSendError.Transport>(thrown.error)
            assertEquals(boom, error.cause, "the original IOException must survive as the cause")
        }

    @Test
    fun retryingDoesNotConsumeTheView() =
        runBlocking {
            val buf = view(128)
            val before = buf.remaining()
            var calls = 0
            writeAbsorbingBackpressure(buf, { if (++calls < 4) 0 else it.remaining() })
            assertEquals(before, buf.remaining(), "a refused write must leave the view intact for the retry")
        }

    @Test
    fun backpressureIsBounded_notUnbounded() =
        runBlocking {
            // The give-up path must actually be reachable: an unbounded retry would hang a send forever
            // on a peer that never drains, which for quiche means a stalled connection with no signal.
            var calls = 0
            val elapsed =
                kotlin.system.measureTimeMillis {
                    runCatching {
                        writeAbsorbingBackpressure(view(), {
                            calls++
                            0
                        }, budget = 20.milliseconds)
                    }
                }
            assertTrue(calls > 1, "the loop must retry at least once before giving up, got $calls")
            assertTrue(elapsed < 2_000, "the budget must bound the wait, took ${elapsed}ms")
        }
}
