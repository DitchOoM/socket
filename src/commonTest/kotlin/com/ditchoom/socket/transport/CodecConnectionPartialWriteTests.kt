package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.SocketWriteStalledException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [ByteSink][com.ditchoom.buffer.flow.ByteSink] may accept a write only in PART — the contract calls
 * the post-write position "the resume point for a partial write's residue". Most sinks in this repo
 * never exercise it (the NIO sockets loop internally, [MemoryTransport] copies wholesale), but a QUIC
 * stream does: quiche's `stream_send` buffers only what the stream's flow-control credit allows at
 * that moment and reports the count.
 *
 * [CodecConnection.send] used to discard that count, so an under-filled write silently truncated the
 * frame. For a self-framing codec that is corruption rather than loss: the peer has already read a
 * length header declaring the full size, so it keeps reading and consumes the frames that FOLLOW as
 * this one's tail — one short write desynchronizes the stream permanently.
 *
 * These tests drive the real [CodecConnection] over sinks that accept a bounded slice per call.
 */
class CodecConnectionPartialWriteTests {
    /**
     * Accepts at most [acceptPerWrite] bytes per call and reports the count. [advanceCursor] picks
     * which of the two real-world sink shapes to imitate: contract-compliant (the cursor is consumed
     * by what was taken, as the NIO sockets do) or quiche-style, which hands the buffer's native
     * address to the native layer and leaves the cursor alone.
     */
    private class PartialAcceptSink(
        private val acceptPerWrite: Int,
        private val advanceCursor: Boolean = true,
    ) : ByteStream {
        val wire = ArrayList<Byte>()
        var writeCalls = 0
            private set

        override val isOpen: Boolean get() = true
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(1.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(1.seconds)

        override suspend fun read(deadline: Duration): ReadResult = ReadResult.End

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten {
            writeCalls++
            val start = buffer.position()
            val take = minOf(acceptPerWrite, buffer.remaining())
            for (i in 0 until take) wire += buffer.readByte()
            if (!advanceCursor) buffer.position(start)
            return BytesWritten(take)
        }

        override suspend fun close() = Unit
    }

    /** A sink that reports zero progress forever — a broken sink, not back-pressure. */
    private class StalledSink : ByteStream {
        override val isOpen: Boolean get() = true
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(1.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(1.seconds)

        override suspend fun read(deadline: Duration): ReadResult = ReadResult.End

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten = BytesWritten(0)

        override suspend fun close() = Unit
    }

    /** Replays [bytes] in [chunk]-sized reads — the receiving half of the stream. */
    private class ReplaySource(
        private val bytes: ByteArray,
        private val chunk: Int = 512,
    ) : ByteStream {
        private var offset = 0

        override val isOpen: Boolean get() = true
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(1.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(1.seconds)

        override suspend fun read(deadline: Duration): ReadResult {
            if (offset >= bytes.size) return ReadResult.End
            val take = minOf(chunk, bytes.size - offset)
            val buf = BufferFactory.Default.allocate(take)
            buf.writeBytes(bytes.copyOfRange(offset, offset + take))
            buf.resetForRead()
            offset += take
            return ReadResult.Data(buf)
        }

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten = fail("the replay source is read-only")

        override suspend fun close() = Unit
    }

    /** The encoded size of [message] under [TestStringCodec] (2-byte length prefix + UTF-8 bytes). */
    private fun encodedSize(message: String) = 2 + message.encodeToByteArray().size

    @Test
    fun sendCompletesAFrameAcrossManyPartialWrites() =
        runTest {
            val message = "x".repeat(20_000)
            val sink = PartialAcceptSink(acceptPerWrite = 1_500)

            // send() is a hand-off to the connection's writer (#382), so close() is what drains it.
            testCodecConnection(sink, TestStringCodec).apply {
                send(message)
                close()
            }

            assertEquals(
                encodedSize(message),
                sink.wire.size,
                "the writer must put the WHOLE encoded frame on the wire, not just the first accepted slice",
            )
            assertTrue(sink.writeCalls > 1, "the sink was never asked to resume (test would not prove anything)")
        }

    /**
     * quiche does not advance the cursor (it passes the buffer's native address to the native layer),
     * so the resume point is derived from the reported COUNT. A sink of that shape must still produce
     * a byte-exact frame — off-by-one resumption here would duplicate or drop bytes rather than
     * truncate, which is just as fatal to a self-framing codec.
     */
    @Test
    fun sendCompletesAFrameWhenTheSinkLeavesTheCursorAlone() =
        runTest {
            val message = (0 until 8_000).joinToString("") { ('a' + (it % 26)).toString() }
            val sink = PartialAcceptSink(acceptPerWrite = 900, advanceCursor = false)

            testCodecConnection(sink, TestStringCodec).apply {
                send(message)
                close()
            }

            assertEquals(encodedSize(message), sink.wire.size, "frame size must survive a non-advancing sink")

            val received = testCodecConnection(ReplaySource(sink.wire.toByteArray()), TestStringCodec).receive().toList()
            assertEquals(listOf(message), received, "the frame must be byte-exact, not merely the right length")
        }

    /**
     * The corruption this guards against: a truncated frame leaves its declared length unfilled, so the
     * peer reads the difference out of the frames that follow. Three messages must arrive as three
     * messages, with no splicing.
     */
    @Test
    fun consecutiveFramesStayAlignedWhenWritesArePartial() =
        runTest {
            val messages = listOf("A".repeat(9_000), "B".repeat(120), "C".repeat(4_500))
            val sink = PartialAcceptSink(acceptPerWrite = 2_048)

            val out = testCodecConnection(sink, TestStringCodec)
            messages.forEach { out.send(it) }
            out.close() // drains the queue; send() only hands off (#382)

            assertEquals(
                messages.sumOf { encodedSize(it) },
                sink.wire.size,
                "every frame must reach the wire in full",
            )

            val received = testCodecConnection(ReplaySource(sink.wire.toByteArray()), TestStringCodec).receive().toList()
            assertEquals(messages, received, "frames must stay aligned — a short write splices the next frame in")
        }

    /**
     * Back-pressure blocks inside `write`; a sink that instead reports zero forever is broken. Fail
     * loudly rather than spin, and rather than return early — returning early IS the truncation.
     *
     * Where that failure is *observed* moved with #382. The write now runs on the connection's writer,
     * not on the caller, so the first `send` only queues and cannot report anything. The writer fails,
     * closes the outbound queue with the stall as its cause, and the next `send` throws it — which is
     * the contract for every write failure now: a queued-but-unwritten message is equivalent to one the
     * network lost, and the caller finds out at its next interaction with the connection.
     *
     * What must not change is that the failure is *loud and typed*: it is still a
     * [SocketWriteStalledException] carrying the same accepted/pending counts, never a silent
     * truncation.
     */
    @Test
    fun aStalledSinkFailsTheWriterLoudlyAndSurfacesAtTheNextSend() =
        runTest {
            val connection = testCodecConnection(StalledSink(), TestStringCodec)

            connection.send("hello") // queued; the writer has not run yet
            testScheduler.runCurrent() // writer picks it up, stalls, and fails the connection

            val failure = assertFailsWith<SocketWriteStalledException> { connection.send("world") }
            assertEquals(0, failure.accepted)
            assertEquals(encodedSize("hello"), failure.pending)
        }

    /**
     * `CodecSender` — the typed **mux-leaf** sender behind `TypedMuxView` — carried a byte-for-byte copy
     * of the same defect, and the consolidation missed it: `Http3StreamWriteConsolidationTest` greps
     * `:socket-http3`, and this lives in the root module, so no ratchet could see it. It was found by
     * pointing Kotlin's `-Xreturn-value-checker` at the tree once buffer marked `ByteSink`
     * `@MustUseReturnValues` — a compile-time check reaches where a source grep structurally cannot.
     *
     * Same failure as [sendCompletesTheFrameAcrossManyPartialWrites]: a sink that accepts a bounded
     * number of bytes per call must still receive every byte, in order, byte-identical.
     *
     * Since #469 the write happens on the sender's own writer, so the drain is `close()` rather than
     * `send()` returning — the same shape this file's bidirectional cases already use.
     */
    @Test
    fun codecSenderCompletesTheFrameAcrossManyPartialWrites() =
        runTest {
            val message = "the mux leaf must not truncate either"
            val sink = PartialAcceptSink(acceptPerWrite = 4)

            val sender = testCodecSender(sink, TestStringCodec)
            sender.send(message)
            sender.close()

            assertEquals(encodedSize(message), sink.wire.size, "the frame was truncated")
            assertTrue(sink.writeCalls > 1, "vacuous: the sink accepted everything in one call")
        }

    /**
     * [CodecSender]'s stalled-sink guard, matching
     * [aStalledSinkFailsTheWriterLoudlyAndSurfacesAtTheNextSend].
     *
     * The failure moved with the writer (#469): a stall can no longer throw out of the `send` that
     * queued the message, because that call no longer touches the sink. It closes the outbound queue
     * with itself as the cause instead, so it reaches the caller at the next `send` — still loudly,
     * still typed, and still carrying how many bytes the sink accepted before it stopped.
     */
    @Test
    fun codecSenderFailsLoudlyAndSurfacesAtTheNextSend() =
        runTest {
            val sender = testCodecSender(StalledSink(), TestStringCodec)

            sender.send("hello") // queued; the writer has not run yet
            testScheduler.runCurrent() // writer picks it up, stalls, and fails the sender

            val failure = assertFailsWith<SocketWriteStalledException> { sender.send("world") }
            assertEquals(0, failure.accepted)
            assertEquals(encodedSize("hello"), failure.pending)
        }
}
