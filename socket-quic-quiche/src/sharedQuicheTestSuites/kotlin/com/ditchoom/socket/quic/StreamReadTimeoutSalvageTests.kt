package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A read whose deadline expires must never destroy bytes the transport already delivered (#414).
 *
 * `streamRead` takes chunks out of `slot.pendingData` **destructively** — the buffer leaves the queue
 * and its ownership moves to the reader. Eight of those takes sit inside `withTimeout`. If the deadline
 * wins the race against the block's return, `withTimeout` throws and the produced value is dropped: the
 * chunk is then unreachable by any later `read()` and by `releaseUndeliveredReads()`, which is silent
 * stream data loss *and* a leak. quiche has already advanced the receive offset and credited flow
 * control for those bytes, so the peer will never resend them — the same permanent hole as #393, reached
 * through the delivery edge instead of the cancellation edge.
 *
 * ## What these tests do and do not prove
 * [ImmediateTimeoutDispatcher] fires `Delay.invokeOnTimeout` synchronously, so the `TimeoutCoroutine`
 * is already cancelled when `withTimeout` starts the body. That pins the **reachable** contract: a read
 * with a chunk queued must return the chunk, not a timeout. It is a real regression guard — but it is
 * NOT a reproduction of the race, and it does not discriminate the #414 fix: kotlinx deliberately
 * protects a body that completes without ever suspending (`startUndispatchedOrReturnIgnoreTimeout`), so
 * this case passes with or without the salvage.
 *
 * The genuinely lossy interleaving is much narrower than it first appears. Once a deadline fires while
 * the read is parked, the resume itself throws at the suspension point, so the take never happens and
 * nothing is lost. The only window left is the timeout landing *between* a completed take and the
 * coroutine registering its result — at which point `JobSupport` discards the value in favour of the
 * cancellation. That is a handful of instructions wide.
 *
 * A 3000-round probe (real threads, deadlines swept across the resume window at millisecond granularity)
 * lost **zero** chunks against the unfixed code. That is not evidence the hole is closed — a
 * millisecond-granularity sweep cannot resolve a nanosecond window — it is evidence the hole is rare,
 * which matches it never having been observed in the field. The salvage is therefore defence in depth
 * for a hole that is real by construction rather than a fix for an observed failure, and no honest test
 * in this file forces it.
 */
class StreamReadTimeoutSalvageTests {
    private val bufferFactory = BufferFactory.deterministic()

    /**
     * A dispatcher that runs timeout actions the instant they are scheduled.
     *
     * `withTimeout` calls `Delay.invokeOnTimeout` *before* it starts the block, so firing synchronously
     * here reproduces "the deadline already expired while the body was mid-flight" without any real
     * elapsed time. Dispatch itself stays undispatched (`isDispatchNeeded = false`) so the body runs on
     * the calling thread and the interleaving is fixed rather than scheduler-dependent.
     */
    @OptIn(InternalCoroutinesApi::class)
    private class ImmediateTimeoutDispatcher : CoroutineDispatcher(), Delay {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = block.run()

        override fun scheduleResumeAfterDelay(
            timeMillis: Long,
            continuation: kotlinx.coroutines.CancellableContinuation<Unit>,
        ) {
            // No test here parks on delay(); resuming immediately keeps the dispatcher total.
            with(continuation) { resumeUndispatched(Unit) }
        }

        override fun invokeOnTimeout(
            timeMillis: Long,
            block: Runnable,
            context: CoroutineContext,
        ): DisposableHandle {
            block.run()
            return DisposableHandle {}
        }
    }

    /**
     * A driver the Fin path never touches — `streamRead` returns from the latched verdict without
     * enqueuing a command — but [DriverStreamAdapter] still needs one to construct.
     */
    private fun testDriver(): QuicheDriver =
        QuicheDriver(
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = StubQuicheApi(),
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            clientMode = false,
            isServer = false,
            keepAliveInterval = null,
            clock = RealDriverClock,
        )

    private fun seededSlot(marker: Byte): Pair<StreamSlot, Int> {
        val slot = StreamSlot(QuicStreamId(0L))
        // A latched terminal verdict routes streamRead onto its non-suspending take-and-return path.
        slot.end = StreamEnd.Fin
        val chunk = bufferFactory.allocate(CHUNK_SIZE)
        repeat(CHUNK_SIZE) { chunk.writeByte(marker) }
        chunk.resetForRead()
        check(slot.pendingData.trySend(chunk).isSuccess) { "seeding pendingData failed" }
        return slot to CHUNK_SIZE
    }

    /** Drain whatever is still queued, freeing it, and report how many chunks were there. */
    private fun drain(slot: StreamSlot): Int {
        var n = 0
        while (true) {
            val b = slot.pendingData.tryReceive().getOrNull() ?: return n
            b.freeIfNeeded()
            n++
        }
    }

    /**
     * The headline case: the deadline expires around a take that already succeeded. The bytes were
     * accepted by the transport before the deadline, so they outrank it — the read must hand them over.
     */
    @Test
    fun timedOutReadDeliversTheChunkItAlreadyDequeued() =
        runQuicTest {
            val (slot, size) = seededSlot(MARKER)
            val adapter = DriverStreamAdapter(testDriver(), slot)

            val result =
                withContext(ImmediateTimeoutDispatcher()) {
                    adapter.streamRead(QuicStreamId(0L), bufferFactory, READ_BUFFER_SIZE, 5.seconds)
                }

            val data = assertIs<ReadResult.Data>(result, "a timed-out read destroyed bytes already dequeued from pendingData (#414)")
            assertEquals(size, data.buffer.remaining(), "delivered chunk was truncated")
            assertEquals(MARKER, data.buffer.readByte(), "delivered chunk is not the one that was queued")
            data.buffer.freeIfNeeded()
            assertEquals(0, drain(slot), "the delivered chunk must not also remain queued")
        }

    /**
     * Conservation, stated as its own assertion: whatever happens to the deadline, the chunk is either
     * returned to the caller or still queued for the next read — never neither. This is the invariant
     * the pre-#414 code broke, and it is the one that matters even if the delivery policy is revisited.
     */
    @Test
    fun aChunkIsNeverBothUndeliveredAndUnqueued() =
        runQuicTest {
            val (slot, _) = seededSlot(MARKER)
            val adapter = DriverStreamAdapter(testDriver(), slot)

            val result =
                runCatching {
                    withContext(ImmediateTimeoutDispatcher()) {
                        adapter.streamRead(QuicStreamId(0L), bufferFactory, READ_BUFFER_SIZE, 5.seconds)
                    }
                }

            val delivered = (result.getOrNull() as? ReadResult.Data)?.also { it.buffer.freeIfNeeded() } != null
            val stillQueued = drain(slot)
            assertEquals(
                1,
                (if (delivered) 1 else 0) + stillQueued,
                "the queued chunk was neither delivered nor left in the queue — it vanished (#414)",
            )
        }

    private companion object {
        const val CHUNK_SIZE = 8
        const val READ_BUFFER_SIZE = 1024
        const val MARKER: Byte = 0x5A
    }
}
