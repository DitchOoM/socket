package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.OutboundQueueFullException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Regression coverage for [#382](https://github.com/ditchoom/socket/issues/382).
 *
 * `CodecConnection.send` used to encode and then call `stream.write` **on the caller's own coroutine**,
 * with no mutex, no queue and no single writer. That gave the API three failure modes, none of them
 * visible in its signature, and these tests measured all three before the fix:
 *
 * - **Not serialized.** Two concurrent senders raced on the sink and interleaved their bytes under a
 *   length-prefix header: `0x00 0x3c 'A'x4 0x00 0x3c 'B'x4 'A'x6 'B'x6 …`, 124 bytes of two spliced
 *   frames that no peer can decode.
 * - **Not atomic.** Cancelling a caller mid-write left a truncated frame whose header still promised
 *   the full length, so the peer read on into the *next* frame: two messages went out and one spliced
 *   message came back — 2 leftover `'X'` bytes followed by 46 `'Y'` bytes stolen from the message
 *   after it — and the peer then went silently deaf.
 * - **Not async.** The caller waited on the peer's socket.
 *
 * The connection now owns a single writer coroutine and [CodecConnection.send] is a hand-off, so the
 * property these tests defend is:
 *
 * > A frame reaches the wire whole or not at all; concurrent sends to one connection cannot interleave;
 * > and no caller ever waits on the peer's socket.
 *
 * ## What changed about *what is even testable*
 *
 * The two original tests asserted the corruption directly. Neither could survive the fix as written,
 * and their author said so: the fix changes the constructor, and more importantly it changes what
 * cancelling a caller can possibly mean. It is no longer "a write stopped half way" — it is only
 * "the message was queued, or it was not". So the cancellation test is not a loosened version of the
 * old one; it asks the question the new design makes answerable, and the bar it holds is *higher*:
 * a cancelled caller must cost at most its own message and must never cost the stream its integrity.
 *
 * ## Deterministic, not a coin flip
 *
 * Everything runs on `kotlinx.coroutines.test`'s virtual-time [runTest] — one logical thread, no real
 * parallelism, nothing depending on OS scheduling. Where a test needs the writer to be *mid-write*, it
 * does not sleep: the sink signals through a [CompletableDeferred] the test awaits, so the interesting
 * moment always lands at the same point. Where it needs the writer to have picked a message up, it
 * calls `testScheduler.runCurrent()` rather than hoping.
 *
 * Every fake sink here is a legitimate `ByteStream`: a sink is allowed to accept a write only in part
 * (see `ByteSink`'s own KDoc), which is exactly why `writeFully` exists.
 */
class CodecConnectionConcurrentSendTests {
    /** The exact bytes a lone [CodecConnection.send] puts on the wire for [message]. */
    private fun cleanFrameFor(message: String): ByteArray {
        val encoded = message.encodeToByteArray()
        val buffer = BufferFactory.Default.allocate(2 + encoded.size)
        TestStringCodec.encode(buffer, message, EncodeContext.Empty)
        buffer.resetForRead()
        return buffer.readByteArray(buffer.remaining())
    }

    private fun hex(byte: Byte): String = "0x" + (byte.toInt() and 0xFF).toString(16).padStart(2, '0')

    /** Groups consecutive identical bytes into (byte, runLength) pairs — a compact interleave dump. */
    private fun runLengthEncode(bytes: ByteArray): List<Pair<Byte, Int>> {
        if (bytes.isEmpty()) return emptyList()
        val runs = mutableListOf<Pair<Byte, Int>>()
        var current = bytes[0]
        var count = 1
        for (i in 1 until bytes.size) {
            if (bytes[i] == current) {
                count++
            } else {
                runs += current to count
                current = bytes[i]
                count = 1
            }
        }
        runs += current to count
        return runs
    }

    private fun describeRun(run: Pair<Byte, Int>): String {
        val (byte, count) = run
        val label =
            when (val c = byte.toInt().toChar()) {
                in 'A'..'Z' -> "'$c'"
                else -> hex(byte)
            }
        return "$label×$count"
    }

    // ── the three properties ────────────────────────────────────────────────────────────────────

    /**
     * **Concurrent sends cannot interleave.**
     *
     * Two `send()` calls on ONE connection over one chunking sink. The sink yields on every accepted
     * chunk, which is the only place a cooperative single-threaded dispatcher can switch coroutines —
     * so before the fix this produced a bit-for-bit reproducible splice of both frames. With one writer
     * draining the queue, the only reachable outcomes are the two legal serializations.
     */
    @Test
    fun concurrentSendsAreWholeFramesOnTheWire() =
        runTest {
            val sink = ChunkingSink(chunkSize = 6)
            val connection = testCodecConnection(sink, TestStringCodec)

            val messageA = "A".repeat(60)
            val messageB = "B".repeat(60)

            val jobA = launch { connection.send(messageA) }
            val jobB = launch { connection.send(messageB) }
            jobA.join()
            jobB.join()
            // send() is a hand-off, so the bytes are not on the wire when it returns. close() drains.
            connection.close()

            val actual = sink.wire.toByteArray()
            val aThenB = cleanFrameFor(messageA) + cleanFrameFor(messageB)
            val bThenA = cleanFrameFor(messageB) + cleanFrameFor(messageA)

            assertTrue(
                actual.contentEquals(aThenB) || actual.contentEquals(bThenA),
                "two concurrent send() calls on one connection must serialize into two whole frames " +
                    "(#382). ${actual.size} bytes reached the wire as: " +
                    runLengthEncode(actual).joinToString(", ") { describeRun(it) } +
                    "\nEither legal ordering is ${aThenB.size} bytes — the 'A' frame then the 'B' " +
                    "frame, or the reverse. Interleaved runs mean a length-prefixed header is " +
                    "promising a payload that is a splice of two senders' messages, which no peer can " +
                    "decode.",
            )
        }

    /**
     * **A message that was queued still arrives whole after its caller is gone.**
     *
     * This is the old "cancelled send truncates the next frame" scenario, asked the way the new design
     * makes it answerable. The caller is cancelled while the writer is provably mid-frame — the sink
     * signals it — and the frame must still complete, because the writer belongs to the connection and
     * not to whoever called `send`.
     *
     * Before the fix the write ran on the cancelled coroutine, so it stopped where it stood and the
     * next message was eaten by the truncated header. Now cancelling the caller cannot reach the write
     * at all.
     */
    @Test
    fun aMessageQueuedBeforeItsCallerVanishedStillArrivesWhole() =
        runTest {
            val sink = SignalsMidFrameSink(chunkSize = 4, signalAfterBytes = 4)
            val connection = testCodecConnection(sink, TestStringCodec)

            val message1 = "X".repeat(50)
            val message2 = "Y".repeat(50)

            val sendJob = launch { connection.send(message1) }
            // Deterministic: proceed only once the writer is genuinely part-way through message1's frame.
            sink.midFrame.await()
            sendJob.cancelAndJoin()

            // A caller with no idea the first sender was cancelled sends the next message normally.
            connection.send(message2)
            connection.close()

            val replay = testCodecConnection(FiniteReplaySource(sink.wire.toByteArray()), TestStringCodec)
            val received = replay.receive().toList()
            replay.close()

            assertEquals(
                listOf(message1, message2),
                received,
                "a caller cancelled while its message was already being written must not truncate it, " +
                    "and must never cost the message after it (#382). The writer belongs to the " +
                    "connection, so cancelling the caller cannot reach the write. Wire was: " +
                    runLengthEncode(sink.wire.toByteArray()).joinToString(", ") { describeRun(it) },
            )
        }

    /**
     * **A cancelled caller costs at most its own message.**
     *
     * Capacity 1 with the writer parked, so the third `send` genuinely suspends waiting for queue
     * space. Cancelling it there must leave the two messages that *were* accepted completely intact —
     * the failure mode being ruled out is a cancellation anywhere in `send` corrupting the stream.
     */
    @Test
    fun aCallerCancelledWaitingForQueueSpaceCostsOnlyItsOwnMessage() =
        runTest {
            val sink = GatedSink()
            val connection = testCodecConnection(sink, TestStringCodec, outboundCapacity = 1)

            connection.send("first")
            testScheduler.runCurrent() // the writer takes "first" and parks inside the sink
            connection.send("second") // fills the one-slot buffer

            val stuck = launch { connection.send("third") } // no room: suspends
            testScheduler.runCurrent()
            assertTrue(stuck.isActive, "test setup: the third send must be suspended on a full queue")
            stuck.cancelAndJoin()

            sink.release()
            connection.close()

            val replay = testCodecConnection(FiniteReplaySource(sink.wire.toByteArray()), TestStringCodec)
            val received = replay.receive().toList()
            replay.close()

            assertEquals(
                listOf("first", "second"),
                received,
                "cancelling a caller that was waiting for queue space must cost exactly its own " +
                    "message and nothing else (#382): the messages already accepted must decode intact.",
            )
        }

    /**
     * **No caller waits on the peer's socket.**
     *
     * The sink never accepts anything, modelling a peer that has stopped draining. `send` must still
     * return — and the proof that it did not secretly wait is that nothing has reached the wire when it
     * does. Before the fix this call was the write, so it could not have returned.
     */
    @Test
    fun aSenderDoesNotWaitForThePeersSocket() =
        runTest {
            val sink = GatedSink()
            val connection = testCodecConnection(sink, TestStringCodec)

            connection.send("hello")

            assertEquals(
                0,
                sink.wire.size,
                "send() must hand off rather than write: it returned, so if any bytes had reached the " +
                    "wire the caller would have had to wait on a peer that never drains (#382).",
            )
            sink.release()
            connection.close()
        }

    /**
     * **Send-then-close keeps its order.**
     *
     * The common shape is `send(goodbye); close()`. Once `send` is a hand-off, a `close()` that did not
     * drain would race ahead of the queued frame and drop it silently, with nothing at the call site to
     * show for it.
     */
    @Test
    fun sendThenCloseDeliversTheQueuedFrame() =
        runTest {
            val sink = ChunkingSink(chunkSize = 8)
            val connection = testCodecConnection(sink, TestStringCodec)

            connection.send("goodbye")
            connection.close()

            assertTrue(
                sink.wire.toByteArray().contentEquals(cleanFrameFor("goodbye")),
                "close() must drain what send() queued, or the send-goodbye-then-close pattern loses " +
                    "the goodbye frame silently (#382). Wire held ${sink.wire.size} bytes.",
            )
        }

    // ── overflow policy ─────────────────────────────────────────────────────────────────────────

    /** [OverflowPolicy.Fail] engages at exactly `outboundCapacity`, and the connection survives it. */
    @Test
    fun failPolicyThrowsAtCapacityAndTheConnectionSurvives() =
        runTest {
            val sink = GatedSink()
            val connection =
                testCodecConnection(sink, TestStringCodec, outboundCapacity = 1, overflowPolicy = OverflowPolicy.Fail)

            connection.send("first")
            testScheduler.runCurrent() // writer takes "first" and parks
            connection.send("second") // fills the one-slot buffer

            val failure = assertFailsWith<OutboundQueueFullException> { connection.send("third") }
            assertEquals(1, failure.capacity, "the exception must carry the configured capacity")

            // The connection is not broken by an overflow — it drains and delivers what it accepted.
            sink.release()
            connection.close()

            val replay = testCodecConnection(FiniteReplaySource(sink.wire.toByteArray()), TestStringCodec)
            assertEquals(
                listOf("first", "second"),
                replay.receive().toList(),
                "a full queue must reject the new message without damaging the accepted ones",
            )
            replay.close()
        }

    /** [OverflowPolicy.DropOldest] evicts the oldest queued message and hands it back. */
    @Test
    fun dropOldestEvictsTheOldestAndHandsItBack() =
        runTest {
            val dropped = mutableListOf<String>()
            val sink = GatedSink()
            val connection =
                testCodecConnection(
                    sink,
                    TestStringCodec,
                    outboundCapacity = 1,
                    overflowPolicy = OverflowPolicy.DropOldest { dropped += it },
                )

            connection.send("first")
            testScheduler.runCurrent() // writer takes "first" and parks
            connection.send("second") // fills the buffer
            connection.send("third") // evicts "second"

            assertEquals(listOf("second"), dropped, "the OLDEST queued message must be the one handed back")

            sink.release()
            connection.close()

            val replay = testCodecConnection(FiniteReplaySource(sink.wire.toByteArray()), TestStringCodec)
            assertEquals(
                listOf("first", "third"),
                replay.receive().toList(),
                "the evicted message must be the only one missing from the wire",
            )
            replay.close()
        }

    /**
     * [OverflowPolicy.DropNewest] rejects the incoming message and hands it back.
     *
     * Implemented in `send` with `trySend` rather than by `BufferOverflow.DROP_LATEST`, because that
     * channel mode does **not** invoke `onUndeliveredElement` for the element it drops — measured — so
     * the obvious implementation would have had a callback that could never fire. This test is what
     * makes that stay true.
     */
    @Test
    fun dropNewestRejectsTheIncomingMessageAndHandsItBack() =
        runTest {
            val dropped = mutableListOf<String>()
            val sink = GatedSink()
            val connection =
                testCodecConnection(
                    sink,
                    TestStringCodec,
                    outboundCapacity = 1,
                    overflowPolicy = OverflowPolicy.DropNewest { dropped += it },
                )

            connection.send("first")
            testScheduler.runCurrent() // writer takes "first" and parks
            connection.send("second") // fills the buffer
            connection.send("third") // rejected

            assertEquals(listOf("third"), dropped, "the NEWEST message must be the one handed back")

            sink.release()
            connection.close()

            val replay = testCodecConnection(FiniteReplaySource(sink.wire.toByteArray()), TestStringCodec)
            assertEquals(
                listOf("first", "second"),
                replay.receive().toList(),
                "the rejected message must be the only one missing from the wire",
            )
            replay.close()
        }

    // ── sinks ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Accepts at most [chunkSize] bytes per call and yields *before* touching the shared, unlocked
     * [wire] — the only place a cooperative single-threaded dispatcher can switch coroutines, and
     * therefore the shape that made the pre-fix interleave reproducible bit for bit.
     */
    private class ChunkingSink(
        private val chunkSize: Int,
    ) : ByteStream {
        val wire = mutableListOf<Byte>()

        override val isOpen: Boolean get() = true
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(1.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(1.seconds)

        override suspend fun read(deadline: Duration): ReadResult = ReadResult.End

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten {
            yield()
            val take = minOf(chunkSize, buffer.remaining())
            repeat(take) { wire += buffer.readByte() }
            return BytesWritten(take)
        }

        override suspend fun close() = Unit
    }

    /**
     * Like [ChunkingSink], but completes [midFrame] once [signalAfterBytes] bytes have been accepted —
     * giving a test a deterministic instant at which the writer is provably part-way through a frame,
     * with no sleeping and no reliance on scheduling.
     */
    private class SignalsMidFrameSink(
        private val chunkSize: Int,
        private val signalAfterBytes: Int,
    ) : ByteStream {
        val wire = mutableListOf<Byte>()
        val midFrame = CompletableDeferred<Unit>()

        override val isOpen: Boolean get() = true
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(1.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(1.seconds)

        override suspend fun read(deadline: Duration): ReadResult = ReadResult.End

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten {
            yield()
            val take = minOf(chunkSize, buffer.remaining())
            repeat(take) { wire += buffer.readByte() }
            if (wire.size >= signalAfterBytes) midFrame.complete(Unit)
            return BytesWritten(take)
        }

        override suspend fun close() = Unit
    }

    /**
     * A peer that has stopped draining: [write] parks until [release] is called, then accepts
     * everything. Models a stalled receive window without any timing dependence.
     */
    private class GatedSink : ByteStream {
        val wire = mutableListOf<Byte>()
        private val gate = CompletableDeferred<Unit>()

        fun release() = gate.complete(Unit).let { }

        override val isOpen: Boolean get() = true
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(1.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(1.seconds)

        override suspend fun read(deadline: Duration): ReadResult = ReadResult.End

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten {
            gate.await()
            val take = buffer.remaining()
            repeat(take) { wire += buffer.readByte() }
            return BytesWritten(take)
        }

        override suspend fun close() = Unit
    }

    /** Replays [bytes] in one shot, then reports clean EOF — a closed peer connection, not a live one. */
    private class FiniteReplaySource(
        private val bytes: ByteArray,
    ) : ByteStream {
        private var offset = 0

        override val isOpen: Boolean get() = true
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(1.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(1.seconds)

        override suspend fun read(deadline: Duration): ReadResult {
            if (offset >= bytes.size) return ReadResult.End
            val buffer = BufferFactory.Default.allocate(bytes.size - offset)
            buffer.writeBytes(bytes, offset, bytes.size - offset)
            buffer.resetForRead()
            offset = bytes.size
            return ReadResult.Data(buffer)
        }

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten = BytesWritten(buffer.remaining())

        override suspend fun close() = Unit
    }
}
