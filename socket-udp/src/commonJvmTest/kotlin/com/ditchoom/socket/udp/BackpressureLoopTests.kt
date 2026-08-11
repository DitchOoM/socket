package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.nio.channels.DatagramChannel as NioChannel

/**
 * The JVM send path's backpressure loop, driven directly with a stub writer and a stub readiness wait.
 *
 * A non-blocking `DatagramChannel.write`/`send` returns 0 when the output buffer is full, and the bug
 * this loop replaced was discarding that — turning local pressure into an invisible drop. The loop is
 * the most intricate part of the change and a real socket cannot exercise it: on loopback the sender's
 * queue never fills, because packets move straight to the receiver's queue. Provoking it for real
 * would need a rate-limited link in a network namespace — Linux-only, privileged, and a test of the
 * kernel rather than of this code. Stubbing the writer covers every branch in microseconds instead,
 * on both JVM and Android.
 *
 * Stubbing the *wait* covers the other half (#303): a refusal is waited out on the socket's own
 * `OP_WRITE` readiness, so the loop must make exactly one write attempt per readiness signal and none
 * in between. With the timer-driven backoff it replaced, a socket that stayed full was re-probed a few
 * hundred times per second-long budget and the readiness seam was never consulted at all —
 * [refusedThroughout_writesOnceAndNeverRePollsTheSocket] and
 * [everyRetryIsPrecededByExactlyOneReadinessWait] fail outright on that mechanism.
 */
class BackpressureLoopTests {
    private fun view(size: Int = 64): ByteBuffer = ByteBuffer.allocate(size)

    /** Readiness stub that must never be consulted — for the paths that complete without waiting. */
    private val neverWaits: suspend (Duration) -> WriteReadiness = { error("the send waited on readiness it did not need") }

    /**
     * Readiness stub reporting [outcome], recording the remaining budget it was handed each time.
     * The recording is the point: it is how a test sees whether a retry was driven by the socket
     * saying it has room, or by a timer.
     */
    private fun recordingWaits(
        seen: MutableList<Duration>,
        outcome: WriteReadiness = WriteReadiness.Writable,
    ): suspend (Duration) -> WriteReadiness =
        { remaining ->
            seen += remaining
            outcome
        }

    @Test
    fun acceptedImmediately_writesOnce() =
        runBlocking {
            var calls = 0
            val buf = view()
            writeAbsorbingBackpressure(buf, {
                calls++
                it.remaining()
            }, neverWaits)
            assertEquals(1, calls, "an accepted datagram must not be retried")
        }

    @Test
    fun refusedThenAccepted_retriesRatherThanDropping() =
        runBlocking {
            var calls = 0
            val buf = view()
            writeAbsorbingBackpressure(buf, { if (++calls < 3) 0 else it.remaining() }, recordingWaits(mutableListOf()))
            assertEquals(3, calls, "a zero return is backpressure — it must be retried, not treated as sent")
        }

    /**
     * The #303 regression, and the sharpest statement of the fix: a socket that never reports itself
     * writable is never asked again. The backoff loop this replaced re-probed on a timer ladder
     * (50µs, doubling to a 5ms cap) until the budget ran out — hundreds of syscalls for one datagram.
     */
    @Test
    fun refusedThroughout_writesOnceAndNeverRePollsTheSocket(): Unit =
        runBlocking {
            var calls = 0
            val seen = mutableListOf<Duration>()
            val thrown =
                assertFailsWith<DatagramSendException> {
                    withTimeout(5_000) {
                        writeAbsorbingBackpressure(view(), {
                            calls++
                            0
                        }, recordingWaits(seen, WriteReadiness.TimedOut), budget = 1.seconds)
                    }
                }
            assertIs<DatagramSendError.WouldBlock>(
                thrown.error,
                "giving up on backpressure must report WouldBlock, not silently return",
            )
            assertEquals(1, calls, "a socket that never reported writable must not be re-probed on a timer, got $calls attempts")
            assertEquals(1, seen.size, "the send must wait on readiness exactly once, then give up")
        }

    /**
     * The other half of #303: every retry is driven by one readiness signal, so attempts and waits stay
     * in lockstep, and each wait is handed only what is left of the send budget. On the timer-driven
     * loop the readiness seam is never consulted at all (0 waits for 3 refusals).
     */
    @Test
    fun everyRetryIsPrecededByExactlyOneReadinessWait() =
        runBlocking {
            var calls = 0
            val seen = mutableListOf<Duration>()
            writeAbsorbingBackpressure(view(), { if (++calls < 4) 0 else it.remaining() }, recordingWaits(seen), budget = 1.seconds)
            assertEquals(4, calls, "three refusals then an accepted write")
            assertEquals(3, seen.size, "each refusal must be waited out on readiness, not on a clock")
            assertTrue(
                seen.zipWithNext().all { (earlier, later) -> later <= earlier },
                "each wait gets the time left in the send budget, so the values must not grow: $seen",
            )
            assertTrue(seen.all { it <= 1.seconds }, "no wait may outlast the send budget: $seen")
        }

    @Test
    fun transportFailure_isReportedTyped(): Unit =
        runBlocking {
            val buf = view()
            val boom = IOException("nope")
            val thrown = assertFailsWith<DatagramSendException> { writeAbsorbingBackpressure(buf, { throw boom }, neverWaits) }
            val error = assertIs<DatagramSendError.Transport>(thrown.error)
            assertEquals(boom, error.cause, "the original IOException must survive as the cause")
        }

    /** A channel closed underneath a parked send reports typed, and never returns as if it had sent. */
    @Test
    fun closedWhileWaiting_isReportedTyped(): Unit =
        runBlocking {
            val thrown =
                assertFailsWith<DatagramSendException> {
                    writeAbsorbingBackpressure(view(), { 0 }, recordingWaits(mutableListOf(), WriteReadiness.Closed))
                }
            val error = assertIs<DatagramSendError.Transport>(thrown.error)
            assertIs<ClosedChannelException>(error.cause, "a close under a parked send must surface as the JVM's own type")
        }

    @Test
    fun retryingDoesNotConsumeTheView() =
        runBlocking {
            val buf = view(128)
            val before = buf.remaining()
            var calls = 0
            writeAbsorbingBackpressure(buf, { if (++calls < 4) 0 else it.remaining() }, recordingWaits(mutableListOf()))
            assertEquals(before, buf.remaining(), "a refused write must leave the view intact for the retry")
        }

    @Test
    fun backpressureIsBounded_notUnbounded() =
        runBlocking {
            // The give-up path must actually be reachable: an unbounded retry would hang a send forever
            // on a peer that never drains, which for quiche means a stalled connection with no signal.
            // The budget bounds the whole send against a monotonic mark, so a wait that burns all of it
            // ends the send — whether readiness arrives late or never.
            var calls = 0
            val slowWait: suspend (Duration) -> WriteReadiness = { remaining ->
                delay(remaining)
                WriteReadiness.TimedOut
            }
            val elapsed =
                kotlin.system.measureTimeMillis {
                    val thrown =
                        assertFailsWith<DatagramSendException> {
                            writeAbsorbingBackpressure(view(), {
                                calls++
                                0
                            }, slowWait, budget = 20.milliseconds)
                        }
                    assertIs<DatagramSendError.WouldBlock>(thrown.error)
                }
            assertTrue(elapsed < 2_000, "the budget must bound the wait, took ${elapsed}ms")
            assertEquals(1, calls, "the socket is probed once per readiness signal, got $calls")
        }

    /**
     * The production wait, on a real socket: [NioDatagramChannelCore.awaitWritable] opens its own
     * `OP_WRITE` selector (the read selector is owned by a parked `receive()`) and reports through the
     * same sealed type the loop branches on. An idle UDP socket has room, so readiness is immediate;
     * once closed, the wait reports Closed instead of burning the caller's budget.
     */
    @OptIn(ExperimentalDatagramApi::class)
    @Test
    fun realSocket_reportsWritableThenClosed() =
        runBlocking(Dispatchers.IO) {
            val nio = NioChannel.open()
            nio.configureBlocking(false)
            nio.bind(InetSocketAddress("127.0.0.1", 0))
            val channel =
                AddressedNioDatagramChannel(
                    channel = nio,
                    localAddress = InternedJvmSocketAddress(nio.localAddress as InetSocketAddress),
                )
            try {
                assertEquals(
                    WriteReadiness.Writable,
                    withTimeout(5_000) { channel.awaitWritable(2.seconds) },
                    "an idle UDP socket has room in its output buffer — readiness must be reported, not timed out",
                )
            } finally {
                channel.close()
            }
            assertEquals(
                WriteReadiness.Closed,
                withTimeout(5_000) { channel.awaitWritable(2.seconds) },
                "a closed channel must end the wait, not park a send until its budget expires",
            )
        }
}
