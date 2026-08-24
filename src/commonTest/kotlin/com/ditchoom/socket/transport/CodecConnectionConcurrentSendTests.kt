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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * **RED — regression coverage for [#382](https://github.com/ditchoom/socket/issues/382), UNFIXED.**
 *
 * `CodecConnection.send` encodes a message and then calls `stream.write(buffer)` **on the caller's own
 * coroutine** (see `CodecConnection.send`). Nothing about that boundary makes it atomic or serialized —
 * there is no mutex, no queue, no single writer — so two callers sharing one connection race directly
 * on the sink, and a caller cancelled mid-write leaves whatever prefix had already reached the wire
 * sitting there under a length header that promises the whole frame.
 *
 * These tests do not simulate the bug with a mock that behaves unlike the real thing: they drive the
 * real [CodecConnection.send] over a real [ByteStream] fake and let the *actual* lack of serialization
 * produce corrupted bytes. Every fake sink here is a legitimate implementation of the `ByteStream`
 * contract (a sink is allowed to accept a write only in part, per [com.ditchoom.buffer.flow.ByteSink]'s
 * own KDoc) — nothing is invented to force a particular outcome, the outcome falls out of `send` having
 * no lock.
 *
 * ## Why this is deterministic, not a coin flip
 * Both tests run under `kotlinx.coroutines.test`'s virtual-time [runTest]: one logical thread, no real
 * parallelism, so nothing here depends on OS thread scheduling. [concurrentSendsInterleaveTheirBytesOnTheWire]
 * forces every context switch itself — the fake sink calls [kotlinx.coroutines.yield] on every accepted
 * chunk, which is the *only* place two coroutines on a cooperative single-threaded dispatcher can trade
 * off — so the interleave pattern is a pure function of launch order and is bit-for-bit identical on
 * every run. [aCancelledSendLeavesAPartialFrameThatCorruptsTheNextOne] never races at all: the sink
 * signals "I am now parked mid-frame" through a [CompletableDeferred] that the test explicitly awaits
 * *before* cancelling, so the cancellation always lands at the exact same byte offset.
 *
 * Both tests are expected to **fail today**, against the current `send(message: T)` single-arg
 * signature. The issue's proposed fix (a connection-owned writer with a required `scope` /
 * `outboundCapacity` / `onOverflow`) changes that signature, so landing it will likely mean this file
 * moves or gets rewritten to call the new API rather than compiling unmodified and turning green — what
 * must survive the rewrite is the *property* each test checks (no interleaving; a cancelled caller
 * cannot corrupt a later, unrelated send). Until #382 is fixed, keep both tests red rather than deleting
 * or loosening them.
 */
class CodecConnectionConcurrentSendTests {
    /** The exact bytes [CodecConnection.send] would put on the wire for [message], sent alone. */
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
            when (byte.toInt().toChar()) {
                'A' -> "'A'"
                'B' -> "'B'"
                else -> hex(byte)
            }
        return "$label×$count"
    }

    /**
     * A [ByteStream] whose [write] accepts at most [chunkSize] bytes per call and calls
     * [kotlinx.coroutines.yield] before taking them, so a call in progress always hands control back to
     * the scheduler between chunks. There is deliberately no lock around [wire]: this is
     * [CodecConnection.send]'s own sink shape today — nothing coordinates two concurrent [ByteStream.write]
     * calls, so whichever caller's turn the scheduler picks writes its chunk next, exactly like an
     * un-synchronized real socket write from two threads would.
     */
    private class UnserializedWireSink(
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
            // The only place a cooperative single-threaded dispatcher can switch coroutines: yield
            // BEFORE touching the shared, unlocked `wire`, so a concurrent sender's own write() call
            // (also parked on a yield) is what runs next — not us again.
            yield()
            val take = minOf(chunkSize, buffer.remaining())
            repeat(take) { wire += buffer.readByte() }
            return BytesWritten(take)
        }

        override suspend fun close() = Unit
    }

    /**
     * **Reason (b): concurrent sends are not serialized.**
     *
     * Two `send()` calls on ONE [CodecConnection], sharing one sink, with no cancellation anywhere. If
     * `send` serialized its callers (a mutex, a queue, anything), the wire would hold one whole frame
     * followed by the other whole frame, in whichever order the scheduler happened to run them — a
     * simple two-outcome fact, unrelated to how many `write()` calls it took to get there. #382 says
     * there is nothing that does that serializing, and this test shows the wire diverging from BOTH of
     * those two legal outcomes.
     */
    @Test
    fun concurrentSendsInterleaveTheirBytesOnTheWire() =
        runTest {
            val sink = UnserializedWireSink(chunkSize = 6)
            val connection = CodecConnection(sink, TestStringCodec)

            val messageA = "A".repeat(60)
            val messageB = "B".repeat(60)

            val jobA = launch { connection.send(messageA) }
            val jobB = launch { connection.send(messageB) }
            jobA.join()
            jobB.join()

            val actual = sink.wire.toByteArray()
            val expectedAThenB = cleanFrameFor(messageA) + cleanFrameFor(messageB)
            val expectedBThenA = cleanFrameFor(messageB) + cleanFrameFor(messageA)

            val runs = runLengthEncode(actual).joinToString(", ") { describeRun(it) }

            assertTrue(
                actual.contentEquals(expectedAThenB) || actual.contentEquals(expectedBThenA),
                "CodecConnection.send has no mutex on the write path (#382): two concurrent send() calls " +
                    "on ONE connection put ${actual.size} bytes on the wire as INTERLEAVED runs, not as " +
                    "two whole frames back to back (either legal serialized ordering is " +
                    "${expectedAThenB.size} bytes: the 'A' frame then the 'B' frame, or vice versa).\n" +
                    "actual byte runs on the wire: $runs\n" +
                    "Neither peer connected to this stream can decode this: a length-prefixed frame's " +
                    "header promises a payload that is now, byte for byte, a splice of two different " +
                    "senders' messages.",
            )
        }

    /**
     * A [ByteStream] that accepts a small first slice of a frame, then — on the very next `write()` call
     * for that same frame — reports it is now parked mid-write via [parkedMidFrame] and suspends until
     * cancelled. Every later `write()` call (i.e. a subsequent, unrelated `send()`) behaves normally and
     * accepts everything at once. This reproduces exactly the shape #382 describes: `send` runs `write`
     * on the caller's own coroutine, so cancelling that coroutine cancels the write, and whatever prefix
     * had already reached [wire] simply stays there.
     */
    private class HangsOnSecondWriteThenRecoversSink(
        private val firstAccept: Int,
    ) : ByteStream {
        val wire = mutableListOf<Byte>()
        val parkedMidFrame = CompletableDeferred<Unit>()
        private var callCount = 0

        override val isOpen: Boolean get() = true
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(1.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(1.seconds)

        override suspend fun read(deadline: Duration): ReadResult = ReadResult.End

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten {
            callCount++
            if (callCount == 2) {
                parkedMidFrame.complete(Unit)
                awaitCancellation()
            }
            val take = if (callCount == 1) minOf(firstAccept, buffer.remaining()) else buffer.remaining()
            repeat(take) { wire += buffer.readByte() }
            return BytesWritten(take)
        }

        override suspend fun close() = Unit
    }

    /**
     * **Reason (a): `send` is not atomic under cancellation.**
     *
     * `message1`'s frame declares a 50-byte payload but only 2 of those bytes ever reach the wire before
     * the sending coroutine is cancelled — a truncated frame under a header that still promises the
     * whole thing, sitting on the stream. `message2` then sends cleanly straight after it, on the same
     * connection, the way a caller who does not know `send()` #1 was cancelled would.
     *
     * The peer (modeled here by a second, real [CodecConnection.receive] over the recorded bytes, no
     * different from decoding straight off a socket) has no way to know frame 1 was short: it reads the
     * declared 50 bytes starting right after the truncated header, which walks 48 bytes into frame 2 —
     * eating frame 2's own header and 46 of its payload bytes as if they were frame 1's tail — and
     * decodes ONE corrupted, spliced message instead of the two that were actually sent. What remains
     * (frame 2's last 4 bytes, headerless) is stranded: its first two bytes get misread as a bogus
     * ~22 KB length prefix, the stream reports needing more data that will never come, and `receive()`
     * simply ends having produced nothing further — the peer going silently deaf that the issue
     * describes, reproduced here as permanently lost bytes rather than an infinite wait only because this
     * test replays a finite, closed buffer instead of a live socket.
     */
    @Test
    fun aCancelledSendLeavesAPartialFrameThatCorruptsTheNextOne() =
        runTest {
            val sink = HangsOnSecondWriteThenRecoversSink(firstAccept = 4)
            val connection = CodecConnection(sink, TestStringCodec)

            val message1 = "X".repeat(50)
            val message2 = "Y".repeat(50)

            val sendJob = launch { connection.send(message1) }
            // Deterministic, not a sleep: proceed only once the sink confirms it is parked INSIDE the
            // second write() call for message1's frame — i.e. mid-frame, not before or after it.
            sink.parkedMidFrame.await()
            sendJob.cancelAndJoin()

            // A caller with no idea send() #1 was cancelled sends the next message normally.
            connection.send(message2)

            assertEquals(
                4,
                sink.wire.size - cleanFrameFor(message2).size,
                "test setup check: message1's frame must have contributed exactly firstAccept=4 bytes " +
                    "before being cancelled, or the rest of this test's byte-offset reasoning is invalid",
            )

            val replay = CodecConnection(FiniteReplaySource(sink.wire.toByteArray()), TestStringCodec)
            val received = replay.receive().toList()

            // The correctness bar: whatever a fix does with the truncated message1 frame (reject it,
            // requeue it, anything), it must NEVER cost message2 — a fully, independently sent frame —
            // its own integrity. So the only acceptable `received` here is exactly [message2].
            val diagnosis =
                if (received.size == 1 && received[0] != message1 && received[0] != message2) {
                    val spliced = received[0]
                    val xCount = spliced.count { it == 'X' }
                    val yCount = spliced.count { it == 'Y' }
                    " Decoded 1 frame that is neither message: a splice of $xCount leftover 'X' byte(s) " +
                        "from message1's truncated frame followed by $yCount 'Y' byte(s) stolen from " +
                        "message2 — message1's declared-but-unwritten length walked straight into " +
                        "message2's header and payload. Spliced frame: $spliced"
                } else {
                    " Decoded ${received.size} frame(s): $received"
                }
            assertEquals(
                listOf(message2),
                received,
                "cancelling send(message1) mid-write left a truncated frame under a header that still " +
                    "promises 50 bytes (#382, reason (a)): only 4 of them were ever written. message2 was " +
                    "then sent cleanly and independently — it must still decode intact on its own, but the " +
                    "truncated header from message1 swallows part of it instead.$diagnosis",
            )
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
            buffer.writeBytes(bytes.copyOfRange(offset, bytes.size))
            buffer.resetForRead()
            offset = bytes.size
            return ReadResult.Data(buffer)
        }

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten = throw UnsupportedOperationException("replay source is read-only")

        override suspend fun close() = Unit
    }
}
