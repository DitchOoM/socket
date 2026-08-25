package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.OutboundQueueFullException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [CodecConnectionConcurrentSendTests]' four properties, asked of the **unidirectional** leaf (#469).
 *
 * #382 gave [CodecConnection] a writer it owns; [CodecSender] — behind
 * [TypedMuxView.openUnidirectional] — kept encoding and writing on the caller's coroutine and kept all
 * three consequences. Being send-only softens none of them: a length-prefixed frame spliced from two
 * senders is undecodable, and a truncated one makes the peer silently deaf for everything after it.
 *
 * These are the acceptance criteria from #469. Five of the six fail against the pre-#469
 * `CodecSender` — measured by mutating `send` back to a caller-coroutine write, not assumed. The
 * sixth is marked where it sits.
 */
class CodecSenderConcurrentSendTests {
    /** The exact bytes a lone [CodecSender.send] puts on the wire for [message]. */
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

    /**
     * **Concurrent sends cannot interleave.**
     *
     * Two `send()` calls on ONE sender over one chunking sink, which yields on every accepted chunk —
     * the only place a cooperative single-threaded dispatcher can switch coroutines, and therefore the
     * shape that made the pre-fix splice reproducible. With one writer draining the queue, the only
     * reachable outcomes are the two legal serializations.
     */
    @Test
    fun concurrentSendsAreWholeFramesOnTheWire() =
        runTest {
            val sink = ChunkingSink(chunkSize = 6)
            val sender = testCodecSender(sink, TestStringCodec)

            val messageA = "A".repeat(60)
            val messageB = "B".repeat(60)

            val jobA = launch { sender.send(messageA) }
            val jobB = launch { sender.send(messageB) }
            jobA.join()
            jobB.join()
            // send() is a hand-off, so the bytes are not on the wire when it returns. close() drains.
            sender.close()

            val actual = sink.wire.toByteArray()
            val aThenB = cleanFrameFor(messageA) + cleanFrameFor(messageB)
            val bThenA = cleanFrameFor(messageB) + cleanFrameFor(messageA)

            assertTrue(
                actual.contentEquals(aThenB) || actual.contentEquals(bThenA),
                "two concurrent send() calls on one CodecSender must serialize into two whole frames " +
                    "(#469). ${actual.size} bytes reached the wire as: " +
                    runLengthEncode(actual).joinToString(", ") { describeRun(it) } +
                    "\nEither legal ordering is ${aThenB.size} bytes. Interleaved runs mean a " +
                    "length-prefixed header is promising a payload spliced from two senders' " +
                    "messages, which no peer can decode.",
            )
        }

    /**
     * **A message that was queued still arrives whole after its caller is gone.**
     *
     * The caller is cancelled while the writer is provably mid-frame — the sink signals it — and the
     * frame must still complete, because the writer belongs to the sender and not to whoever called
     * `send`. Before #469 the write ran on the cancelled coroutine, so it stopped where it stood and
     * the truncated header ate the message after it.
     */
    @Test
    fun aMessageQueuedBeforeItsCallerVanishedStillArrivesWhole() =
        runTest {
            val sink = SignalsMidFrameSink(chunkSize = 4, signalAfterBytes = 4)
            val sender = testCodecSender(sink, TestStringCodec)

            val message1 = "X".repeat(50)
            val message2 = "Y".repeat(50)

            val sendJob = launch { sender.send(message1) }
            // Deterministic: proceed only once the writer is genuinely part-way through message1's frame.
            sink.midFrame.await()
            sendJob.cancelAndJoin()

            // A caller with no idea the first sender was cancelled sends the next message normally.
            sender.send(message2)
            sender.close()

            assertContentEquals(
                cleanFrameFor(message1) + cleanFrameFor(message2),
                sink.wire.toByteArray(),
                "a caller cancelled while its message was already being written must not truncate it, " +
                    "and must never cost the message after it (#469). The writer belongs to the " +
                    "sender, so cancelling the caller cannot reach the write. Wire was: " +
                    runLengthEncode(sink.wire.toByteArray()).joinToString(", ") { describeRun(it) },
            )
        }

    /**
     * **A sender does not wait for the peer's socket.**
     *
     * The sink never drains until released. `send` returning with nothing on the wire is the whole
     * point of the hand-off — before #469 this call could not return at all.
     */
    @Test
    fun aSenderDoesNotWaitForThePeersSocket() =
        runTest {
            val sink = GatedSink()
            val sender = testCodecSender(sink, TestStringCodec)

            sender.send("hello")

            assertEquals(
                0,
                sink.wire.size,
                "send() must hand off rather than write: it returned, so if any bytes had reached the " +
                    "wire the caller would have had to wait on a peer that never drains (#469).",
            )
            sink.release()
            sender.close()
        }

    /**
     * **Send-then-close keeps its order.**
     *
     * The common shape is `send(goodbye); close()`. Once `send` is a hand-off, a `close()` that did not
     * drain would race ahead of the queued frame and drop it silently.
     *
     * ⚠️ Unlike the other five, this one **passes against the pre-#469 sender too** — a caller-coroutine
     * write has already put the frame on the wire by the time `close()` runs, so there is nothing for a
     * missing drain to lose. It is recorded here as a regression guard on the new design, not as
     * evidence about the old one. The mutation run was 5/6 red, and this is the 6th.
     */
    @Test
    fun sendThenCloseDeliversTheQueuedFrame() =
        runTest {
            val sink = ChunkingSink(chunkSize = 8)
            val sender = testCodecSender(sink, TestStringCodec)

            sender.send("goodbye")
            sender.close()

            assertContentEquals(
                cleanFrameFor("goodbye"),
                sink.wire.toByteArray(),
                "close() must drain the queue: a message accepted by send() and then dropped by " +
                    "close() vanishes with nothing at the call site to show for it (#469)",
            )
        }

    /**
     * **The overflow policy engages at exactly capacity.**
     *
     * Capacity 2 with the writer parked on a gate, so the queue is the only thing holding messages.
     * Two are accepted; the third is the first overflow. [OverflowPolicy.Fail] makes that observable at
     * the send site, and the sender must survive it — a full queue is back-pressure, not a fault.
     */
    @Test
    fun failPolicyThrowsAtExactlyCapacityAndTheSenderSurvives() =
        runTest {
            val sink = GatedSink()
            val sender =
                testCodecSender(
                    sink,
                    TestStringCodec,
                    outboundCapacity = 2,
                    overflowPolicy = OverflowPolicy.Fail,
                )

            // The writer takes the first message off the queue immediately and parks on the gate
            // holding it, so the queue itself only fills from the next send onward.
            sender.send("in-flight")
            yield()
            sender.send("queued-1")
            sender.send("queued-2")

            assertFailsWith<OutboundQueueFullException>(
                "the third queued message is the first that does not fit a capacity-2 queue, and " +
                    "OverflowPolicy.Fail must say so at the send site (#469)",
            ) {
                sender.send("overflow")
            }

            sink.release()
            sender.close()

            assertContentEquals(
                cleanFrameFor("in-flight") + cleanFrameFor("queued-1") + cleanFrameFor("queued-2"),
                sink.wire.toByteArray(),
                "an overflow must cost only the message that overflowed: the three that were accepted " +
                    "still reach the wire whole and in order",
            )
        }

    /**
     * **DropOldest hands back the message it evicted.**
     *
     * The counterpart to [failPolicyThrowsAtExactlyCapacityAndTheSenderSurvives]: `send` never fails,
     * and the caller learns which message was shed through the policy's callback rather than an
     * exception.
     */
    @Test
    fun dropOldestEvictsTheOldestAndHandsItBack() =
        runTest {
            val sink = GatedSink()
            val evicted = mutableListOf<String>()
            val sender =
                testCodecSender(
                    sink,
                    TestStringCodec,
                    outboundCapacity = 2,
                    overflowPolicy = OverflowPolicy.DropOldest { evicted += it },
                )

            sender.send("in-flight")
            yield()
            sender.send("queued-1")
            sender.send("queued-2")
            sender.send("queued-3")

            assertEquals(
                listOf("queued-1"),
                evicted,
                "DropOldest must evict the oldest queued message and hand exactly that one back (#469)",
            )

            sink.release()
            sender.close()
        }

    // ── sinks ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Accepts at most [chunkSize] bytes per call and yields *before* touching the shared, unlocked
     * [wire] — the only place a cooperative single-threaded dispatcher can switch coroutines, and
     * therefore the shape that made the pre-fix interleave reproducible bit for bit.
     */
    private class ChunkingSink(
        private val chunkSize: Int,
    ) : ByteSink {
        val wire = mutableListOf<Byte>()

        override val isOpen: Boolean get() = true
        override val writePolicy: WritePolicy = WritePolicy.Bounded(1.seconds)

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
     * a deterministic instant at which the writer is provably part-way through a frame, with no
     * sleeping and no reliance on scheduling.
     */
    private class SignalsMidFrameSink(
        private val chunkSize: Int,
        private val signalAfterBytes: Int,
    ) : ByteSink {
        val wire = mutableListOf<Byte>()
        val midFrame = CompletableDeferred<Unit>()

        override val isOpen: Boolean get() = true
        override val writePolicy: WritePolicy = WritePolicy.Bounded(1.seconds)

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
    private class GatedSink : ByteSink {
        val wire = mutableListOf<Byte>()
        private val gate = CompletableDeferred<Unit>()

        fun release() = gate.complete(Unit).let { }

        override val isOpen: Boolean get() = true
        override val writePolicy: WritePolicy = WritePolicy.Bounded(1.seconds)

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
}
