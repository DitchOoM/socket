package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Failure containment on a core connection against a core server, on every platform: what one stream operation throws
 * fails that operation, what processing one connection throws ends that connection, and only the loop's own failures end
 * every connection. The `Error` tiers are the JVM's (CoreErrorTierTests): they need a thread's uncaught-error handler.
 */
class CoreFailureTierTests {
    @Test
    fun anOperationThatThrowsFailsItsCallerAndLeavesTheOtherStreamsWorking() =
        withFailureRig("CoreFailureTierTests.anOperationThatThrowsFailsItsCallerAndLeavesTheOtherStreamsWorking") { rig ->
            val connection = rig.connection
            val broken = connection.openStream()
            val healthy = connection.openStream()
            val planted = IllegalStateException("planted in a stream operation")

            val failure =
                assertFailsWith<IllegalStateException> {
                    val op =
                        ReadOp(
                            broken.streamId,
                            Duration.INFINITE,
                            connection.deadlines,
                            rig.conn,
                            IdleRead(),
                            ThrowingFactory(planted),
                            ReadSizing(),
                        )
                    rig.conn.submit(op)
                    op.answer()
                }
            assertTrue(failure === planted || failure.cause === planted, "the caller got the planted exception: $failure")

            val payload = Random(1).nextBytes(1024)
            assertContentEquals(payload, echoOver(healthy, payload), "echo on the other stream, same connection")
            assertIs<EdgeEnd.Open>(connection.end(), "the connection did not end")
        }

    @Test
    fun aConnectionWorkThatThrowsEndsThatConnectionAndTheLoopKeepsRunning() =
        withFailureRig("CoreFailureTierTests.aConnectionWorkThatThrowsEndsThatConnectionAndTheLoopKeepsRunning") { rig ->
            val stream = rig.connection.openStream()
            val planted = IllegalStateException("planted in connection work")
            val parkedRead = async { runCatching { stream.read(10.seconds) { } } }
            delay(200)

            rig.conn.submit(ThrowingWork(planted))

            val end = assertIs<ConnEnd.ProcessingFailed>(rig.ended())
            assertSame(planted, end.thrown.throwable)
            val read = assertIs<QuicCloseException>(parkedRead.await().exceptionOrNull())
            assertSame(planted, read.cause)
            assertEquals(QuicCloseCircumstance.ProcessingFailed, read.circumstance)
            assertEquals(QuicCloseReason.ByLocal(QuicError.ProcessingFailed), read.closeReason)

            val first = rig.runOnLoop()
            val second = rig.runOnLoop()
            assertTrue(second > first, "the loop kept passing: $first then $second")
            val later = LateWork()
            rig.conn.submit(later)
            assertEquals(1, withTimeout(5.seconds) { later.gone.await() }, "work for the failed connection is answered as gone")
        }

    @Test
    fun anOperationQueuedBehindTheWorkThatEndedTheConnectionFailsWithThatCause() =
        withFailureRig("CoreFailureTierTests.anOperationQueuedBehindTheWorkThatEndedTheConnectionFailsWithThatCause") { rig ->
            val stream = rig.connection.openStream()
            val planted = IllegalStateException("planted in connection work")
            val hold = HoldThenThrow(planted)
            rig.conn.submit(hold)
            withTimeout(10.seconds) { hold.inside.await() }
            // The loop is inside the connection, so the read is queued behind the work that is about to end it.
            val queued = rig.conn.counts.leftForHolder
            val read = async(Dispatchers.IO) { runCatching { stream.read(10.seconds) { } } }
            while (rig.conn.counts.leftForHolder == queued) yield()
            hold.release()

            val failure = assertIs<QuicCloseException>(read.await().exceptionOrNull())
            assertSame(planted, failure.cause, "the read ended with the connection's own failure: $failure")
            assertEquals(QuicCloseCircumstance.ProcessingFailed, failure.circumstance)
        }

    @Test
    fun aLoopThatFailsEndsEveryConnectionWithTheLoopFailure() =
        withFailureRig("CoreFailureTierTests.aLoopThatFailsEndsEveryConnectionWithTheLoopFailure") { rig ->
            val planted = IllegalStateException("planted in the loop")
            rig.loop.port.submit(ThrowingLoopWork(planted))

            val end = assertIs<ConnEnd.LoopFailed>(rig.ended())
            assertSame(planted, end.thrown.throwable)
        }

    @Test
    fun anOperationStartedAfterTheConnectionEndedFailsInsteadOfWaiting() =
        withFailureRig("CoreFailureTierTests.anOperationStartedAfterTheConnectionEndedFailsInsteadOfWaiting") { rig ->
            val stream = rig.connection.openStream()
            rig.loop.port.submit(ThrowingLoopWork(IllegalStateException("planted in the loop")))
            assertIs<ConnEnd.LoopFailed>(rig.ended())

            assertFailsWith<QuicCloseException> { withTimeout(5.seconds) { stream.read(10.seconds) { } } }
            assertFailsWith<QuicCloseException> { withTimeout(5.seconds) { rig.connection.openStream() } }
        }

    @Test
    fun aConnectionWorkThatThrowsInsideACallerEndsThatConnectionAndTheCallerLeaves() =
        withFailureRig("CoreFailureTierTests.aConnectionWorkThatThrowsInsideACallerEndsThatConnectionAndTheCallerLeaves") { rig ->
            val planted = IllegalStateException("planted in connection work, run by a caller")
            runOnACaller(rig) { OnACaller(planted) }

            val end = assertIs<ConnEnd.ProcessingFailed>(rig.ended())
            assertSame(planted, end.thrown.throwable)
            assertTrue(rig.conn.counts.enteredInPlace >= 1, "a caller ran the work, not the loop")
            val later = LateWork()
            rig.conn.submit(later, CallerPlace.MayEnter)
            assertEquals(1, withTimeout(5.seconds) { later.gone.await() }, "work for the failed connection is answered as gone")
            assertTrue(rig.runOnLoop() > 0, "the loop kept running")
        }

    @Test
    fun aFullSocketHandsTheCallersDatagramsToTheLoopWhichSendsThem() =
        withFailureRig("CoreFailureTierTests.aFullSocketHandsTheCallersDatagramsToTheLoopWhichSendsThem") { rig ->
            val stream = rig.connection.openStream()
            val payload = Random(9).nextBytes(20_000)
            val head = BufferFactory.network().allocate(HEAD_BYTES)
            head.writeBytes(payload, 0, HEAD_BYTES)
            head.resetForRead()
            rig.io.refuseCallerSends(1)
            // A caller sends the first bytes in place: the full socket refuses them, and the loop must finish the send.
            sendOnACaller(rig, QuicStreamId(0), head)
            assertEquals(1L, rig.loop.callerCounts.sendsDeferred, "the caller's send was deferred to the loop")
            val received = ReceivedBytes(payload.size)
            coroutineScope {
                val reader =
                    async {
                        while (received.size < payload.size) {
                            if (stream.read(10.seconds) { received.add(it) } !is ScopedRead.Data<*>) break
                        }
                    }
                stream.writeFully(
                    BufferFactory.network().allocate(payload.size - HEAD_BYTES).apply {
                        writeBytes(payload, HEAD_BYTES, payload.size - HEAD_BYTES)
                        resetForRead()
                    },
                    10.seconds,
                )
                reader.await()
            }
            assertContentEquals(payload, received.toByteArray(), "echo of what the loop sent after the socket refused the caller")
        }

    private companion object {
        const val HEAD_BYTES = 1_000
    }
}
