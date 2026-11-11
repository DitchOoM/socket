package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.MemoryTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The read gate of a [QuicStreamOrigin.Early] stream, against a scripted confirmation. */
@OptIn(QuicEngineSpi::class)
class QuicStreamOriginTests {
    /** A stream over an in-memory pair whose peer has already written "hi", gated by [confirmation]. */
    private suspend fun stream(origin: (CompletableDeferred<Unit>) -> QuicStreamOrigin): Pair<QuicByteStream, CompletableDeferred<Unit>> {
        val (local, peer) = MemoryTransport.createPair(TransportConfig(bufferFactory = BufferFactory.Default))
        peer.write(hi())
        val confirmation = CompletableDeferred<Unit>()
        return QuicByteStream(QuicStreamId(0), local, origin(confirmation)) to confirmation
    }

    private fun early(confirmation: CompletableDeferred<Unit>) = QuicStreamOrigin.Early { confirmation.await() }

    private fun hi() =
        BufferFactory.Default.allocate(2).also {
            it.writeBytes("hi".encodeToByteArray())
            it.resetForRead()
        }

    private fun ReadResult.release() {
        if (this is ReadResult.Data) buffer.freeIfNeeded()
    }

    @Test
    fun aConfirmedStreamReadsAtOnceAndMarkingItReplaySafeChangesNothing() =
        runTest {
            val (stream, _) = stream { QuicStreamOrigin.Confirmed }
            stream.markReplaySafe()
            assertIs<ReadResult.Data>(stream.read(5.seconds).also { it.release() })
            assertSame(QuicStreamOrigin.Confirmed, stream.origin)
        }

    @Test
    fun anEarlyStreamsReadWaitsForConfirmation() =
        runTest {
            val (stream, confirmation) = stream(::early)
            val read = async { stream.read(5.seconds) }
            runCurrent()
            assertFalse(read.isCompleted, "the read handed over early bytes before the handshake was confirmed")
            confirmation.complete(Unit)
            assertIs<ReadResult.Data>(read.await().also { it.release() })
        }

    @Test
    fun markingAWaitingReadReplaySafeReleasesIt() =
        runTest {
            val (stream, _) = stream(::early)
            val read = async { stream.read(5.seconds) }
            runCurrent()
            assertFalse(read.isCompleted)
            stream.markReplaySafe()
            assertIs<ReadResult.Data>(read.await().also { it.release() }, "the waiting read proceeds without confirmation")
        }

    @Test
    fun aHandshakeThatFailsFailsTheWaitingReadWithTheClose() =
        runTest {
            val close = QuicCloseException(QuicCloseReason.Unspecified, "handshake failed")
            val (stream, _) = stream { QuicStreamOrigin.Early { throw close } }
            val thrown = assertFailsWith<QuicCloseException> { stream.read(5.seconds) }
            assertEquals(close.closeReason, thrown.closeReason)
        }

    @Test
    fun aStreamMarkedBeforeReadingNeverWaits() =
        runTest {
            val (stream, confirmation) = stream(::early)
            stream.markReplaySafe()
            assertIs<ReadResult.Data>(stream.read(5.seconds).also { it.release() })
            assertTrue(!confirmation.isCompleted)
        }
}
