package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicStreamClosedException
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A write that was to end our side and stopped before quiche took its last byte, on a real core client and server over
 * a 60 ms round trip, on virtual time. Stopped after quiche took part of it, the stream is reset: the peer never reads
 * the part as the whole. Stopped before quiche took any of it, our side is open again and a close sends the FIN. Once
 * quiche took the FIN, our side is over however the call ended. The stream's flow window, a mebibyte, is filled first by
 * a write nobody reads, so a finishing write waits with nothing taken; a larger one waits with part of it taken.
 */
class FinCutShortVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun aDeadlineAfterQuicheTookPartOfAFinishingWriteResetsTheStream() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val stream = pair.client.connection.openCoreStream()
            var result: Finished = Finished.All(-1)
            launch { result = stream.writeAndFinish(bytes(2 * WINDOW), 1.seconds) }
            pair.runFor(2.seconds)

            val cut = assertIs<Finished.Cut>(result, "the deadline passed with part of the write taken")
            assertEquals(WINDOW, cut.accepted, "quiche took the stream's window")
            assertIs<OpFailure.DeadlinePassed>(cut.failure)
            assertSame(ScopedRead.Reset, endOf(pair, serverSide.acceptStream()), "the peer is told by a reset, never a FIN")
        }

    @Test
    fun aDeadlineBeforeQuicheTookAnyOfAFinishingWriteLeavesOurSideOpenForTheCloseToFinish() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val stream = pair.client.connection.openCoreStream()
            filled(pair, stream)
            var failed: Throwable? = null
            launch { failed = runCatching { stream.writeAndFinish(bytes(REQUEST), 1.seconds) }.exceptionOrNull() }
            pair.runFor(2.seconds)
            assertIs<CancellationException>(failed, "nothing was taken: the deadline raises, as a write's does")

            closed(pair, stream)
            assertSame(ScopedRead.End, endOf(pair, serverSide.acceptStream()), "the close sent the FIN")
        }

    @Test
    fun aCancellationAfterQuicheTookPartOfAFinishingWriteResetsTheStream() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val stream = pair.client.connection.openCoreStream()
            val caller = Held()
            val writer = caller.launch { stream.writeAndFinish(bytes(2 * WINDOW), 1.hours) }
            caller.runAll()
            pair.runFor(200.milliseconds)
            writer.cancel()
            pair.runFor(10.milliseconds)
            caller.runAll()
            assertTrue(writer.isCancelled)

            assertSame(ScopedRead.Reset, endOf(pair, serverSide.acceptStream()), "the peer is told by a reset, never a FIN")
            assertFailsWith<QuicStreamClosedException> { stream.write(bytes(REQUEST), 1.hours) }
        }

    @Test
    fun aCancellationBeforeQuicheTookAnyOfAFinishingWriteLeavesOurSideOpenForTheCloseToFinish() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val stream = pair.client.connection.openCoreStream()
            filled(pair, stream)
            val caller = Held()
            val writer = caller.launch { stream.writeAndFinish(bytes(REQUEST), 1.hours) }
            caller.runAll()
            pair.runFor(10.milliseconds)
            writer.cancel()
            pair.runFor(10.milliseconds)
            caller.runAll()
            assertTrue(writer.isCancelled)

            closed(pair, stream)
            assertSame(ScopedRead.End, endOf(pair, serverSide.acceptStream()), "the close sent the FIN")
        }

    /**
     * quiche took the FIN, and the caller was cancelled before it resumed with the answer: our side is over. A later write
     * fails as on a finished stream, and a close returns.
     */
    @Test
    fun aCancellationAfterQuicheTookTheFinLeavesOurSideFinished() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val stream = pair.client.connection.openCoreStream()
            filled(pair, stream)
            val caller = Held()
            val writer = caller.launch { stream.writeAndFinish(bytes(REQUEST), 1.hours) }
            caller.runAll()
            pair.runFor(10.milliseconds)
            // The server reads: the window opens, quiche takes the request and its FIN, and the answer waits for the caller.
            val got = LongArray(1)
            var end: ScopedRead<*> = ScopedRead.Data(Unit)
            launch { end = readToEnd(serverSide.acceptStream(), got) }
            pair.runUntil { end !is ScopedRead.Data<*> }
            writer.cancel()
            caller.runAll()

            assertSame(ScopedRead.End, end)
            assertEquals((WINDOW + REQUEST).toLong(), got[0])
            assertFailsWith<QuicStreamClosedException> { stream.write(bytes(REQUEST), 1.hours) }
            closed(pair, stream)
        }

    /** A typed send that was to end our side, cancelled before quiche took any of its frame: a close sends the FIN. */
    @Test
    fun aTypedFinishingSendCancelledBeforeQuicheTookAnyOfItLeavesOurSideOpenForTheCloseToFinish() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val stream = pair.client.connection.openTypedStream(NoteCodec)
            var queued = 0
            launch {
                repeat(WINDOW / FRAME) {
                    stream.send(FRAME_NOTE)
                    queued++
                }
            }
            pair.runUntil { queued == WINDOW / FRAME }
            val caller = Held()
            val sender = caller.launch { stream.sendAndFinish(FRAME_NOTE) }
            caller.runAll()
            pair.runFor(10.milliseconds)
            sender.cancel()
            pair.runFor(10.milliseconds)
            caller.runAll()
            assertTrue(sender.isCancelled)

            var closing = false
            launch {
                stream.close()
                closing = true
            }
            pair.runUntil { closing }
            val got = LongArray(1)
            assertSame(ScopedRead.End, readToEnd(pair, serverSide.acceptStream(), got), "the close sent the FIN")
            assertEquals(WINDOW.toLong(), got[0], "every frame sent before arrived, and nothing of the cancelled one")
        }

    /** Writes the stream's whole flow window, which nobody reads. */
    private suspend fun CoroutineScope.filled(
        pair: VirtualPair,
        stream: CoreStream,
    ) {
        var done = false
        launch {
            stream.writeFully(bytes(WINDOW), 1.hours)
            done = true
        }
        pair.runUntil { done }
    }

    private suspend fun CoroutineScope.closed(
        pair: VirtualPair,
        stream: CoreStream,
    ) {
        var done = false
        launch {
            stream.close()
            done = true
        }
        pair.runUntil { done }
    }

    /** How the server's read of [stream] ended, reading as the pair runs. */
    private suspend fun CoroutineScope.endOf(
        pair: VirtualPair,
        stream: QuicByteStream,
    ): ScopedRead<*> = readToEnd(pair, stream, LongArray(1))

    private suspend fun CoroutineScope.readToEnd(
        pair: VirtualPair,
        stream: QuicByteStream,
        got: LongArray,
    ): ScopedRead<*> {
        var end: ScopedRead<*> = ScopedRead.Data(Unit)
        launch { end = readToEnd(stream, got) }
        pair.runUntil { end !is ScopedRead.Data<*> }
        return end
    }

    private suspend fun readToEnd(
        stream: QuicByteStream,
        got: LongArray,
    ): ScopedRead<*> {
        while (true) {
            val read = stream.read(1.hours) { got[0] += it.remaining() }
            if (read !is ScopedRead.Data<*>) return read
        }
    }

    /** A caller whose coroutines run only when the test says: a cancellation can land before it resumes. */
    private class Held : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        private val scope = CoroutineScope(this + Job())

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = tasks.put(block)

        fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

        fun runAll() {
            while (true) (tasks.poll() ?: return).run()
        }
    }

    private fun bytes(size: Int) =
        BufferFactory.Default.allocate(size).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        /** The stream's flow window: [com.ditchoom.socket.quic.FlowControl]'s initial bidirectional stream data. */
        const val WINDOW = 1_048_576
        const val REQUEST = 512

        /** A frame of exactly 1 KiB, so the window holds a whole number of them. */
        const val FRAME = 1_024
        val FRAME_NOTE = Note(1, "f".repeat(FRAME - NoteCodec.HEADER - 4))
    }
}
