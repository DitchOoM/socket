package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadPolicy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ScopedReadTests {
    @Test
    fun aValueIsTakenWithoutAWhen() {
        assertEquals(7, ScopedRead.data(7).getOrElse { 0 })
        assertEquals("Data(7)", ScopedRead.data(7).toString())
    }

    @Test
    fun everyReasonForNoDataReachesTheCallerAsItself() {
        val reasons =
            listOf(
                ScopedRead.End.PeerFinished,
                ScopedRead.End.PeerReset(QuicAppErrorCode(0x10cL)),
                ScopedRead.End.ConnectionEnded(QuicConnectionEnd.IdleTimeout),
                ScopedRead.DeadlinePassed,
                ScopedRead.Broken(QuicFrameFailure.Unframed),
            )
        for (reason in reasons) {
            val read: ScopedRead<Int> = ScopedRead.noData(reason)
            assertSame(reason, read.fold({ ScopedRead.DeadlinePassed }, { it }))
            assertEquals(-1, read.getOrElse { -1 })
            when (reason) {
                is ScopedRead.End.PeerFinished, is ScopedRead.End.PeerReset, is ScopedRead.End.ConnectionEnded ->
                    assertIs<ScopedRead.End>(reason)
                is ScopedRead.DeadlinePassed, is ScopedRead.Broken -> Unit
            }
        }
    }

    @Test
    fun aBlockThatReturnsAReasonAsItsValueIsStillData() {
        val read: ScopedRead<ScopedRead.NoData> = ScopedRead.data(ScopedRead.End.PeerFinished)
        assertEquals("data", read.fold({ "data" }, { "no data" }))
        assertSame(ScopedRead.End.PeerFinished, read.getOrElse { ScopedRead.DeadlinePassed })
    }

    @Test
    fun readEachRunsItsBlockOnEveryChunkAndReturnsWhyTheStreamStopped() =
        runTest {
            val stream = ScriptedReceive(chunks = 3, then = ScopedRead.End.PeerReset(QuicAppErrorCode(9L)))
            var seen = 0
            val why = stream.readEach { seen += it.remaining() }
            assertEquals(3 * CHUNK, seen)
            assertEquals(QuicAppErrorCode(9L), assertIs<ScopedRead.End.PeerReset>(why).code)
        }

    /** A receive stream that gives [chunks] chunks, then [then]. */
    private class ScriptedReceive(
        private var chunks: Int,
        private val then: ScopedRead.NoData,
    ) : QuicReceiveStream {
        override val id = QuicStreamId(3)
        override val readPolicy: ReadPolicy = ReadPolicy.UntilClosed
        override val origin: QuicStreamOrigin = QuicStreamOrigin.Confirmed

        override fun markReplaySafe() = Unit

        override suspend fun <R> read(
            policy: ReadPolicy,
            block: suspend (ReadBuffer) -> R,
        ): ScopedRead<R> {
            if (chunks == 0) return ScopedRead.noData(then)
            chunks--
            val buffer = BufferFactory.Default.allocate(CHUNK)
            buffer.writeBytes(ByteArray(CHUNK))
            buffer.resetForRead()
            return ScopedRead.data(block(buffer))
        }

        override fun stop(code: QuicAppErrorCode) = Unit
    }

    private companion object {
        const val CHUNK = 16
    }
}
