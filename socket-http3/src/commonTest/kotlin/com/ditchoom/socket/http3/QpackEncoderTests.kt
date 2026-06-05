package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * [QpackEncoder] round-trips against a wired [QpackDecoder] — the encoder's instructions feed the
 * decoder and the decoder's acknowledgments feed back, mirroring the two QPACK uni streams in
 * miniature. This is the integration test for the whole dynamic stack (prefix + table + instructions
 * + both stateful halves).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QpackEncoderTests {
    private val pool = BufferPool(threadingMode = ThreadingMode.SingleThreaded, factory = BufferFactory.Default)

    /**
     * A wired encoder+decoder. Instructions are *queued* rather than delivered synchronously — in a
     * real connection they cross separate QPACK uni streams, so the encoder never re-enters itself
     * while encoding. [pump] flushes both queues (and the increments they cascade) between operations.
     */
    private inner class Pair(
        maxCapacity: Long,
        maxBlockedStreams: Long = 0,
    ) {
        private val encoderToDecoder = ArrayDeque<QpackEncoderInstruction>() // our encoder stream → peer decoder
        private val decoderToEncoder = ArrayDeque<QpackDecoderInstruction>() // peer decoder stream → our encoder
        val encoder = QpackEncoder(maxCapacity, maxBlockedStreams) { encoderToDecoder.addLast(it) }
        val decoder = QpackDecoder(maxCapacity) { decoderToEncoder.addLast(it) }

        suspend fun pump() {
            while (encoderToDecoder.isNotEmpty() || decoderToEncoder.isNotEmpty()) {
                while (encoderToDecoder.isNotEmpty()) decoder.applyEncoderInstruction(encoderToDecoder.removeFirst())
                while (decoderToEncoder.isNotEmpty()) encoder.processDecoderInstruction(decoderToEncoder.removeFirst())
            }
        }

        /** Deliver only our encoder-stream instructions (inserts) to the decoder — leaves acks pending. */
        suspend fun flushEncoderStream() {
            while (encoderToDecoder.isNotEmpty()) decoder.applyEncoderInstruction(encoderToDecoder.removeFirst())
        }

        /** Deliver only the decoder-stream instructions (acks/cancellations/increments) back to the encoder. */
        suspend fun flushDecoderStream() {
            while (decoderToEncoder.isNotEmpty()) encoder.processDecoderInstruction(decoderToEncoder.removeFirst())
        }
    }

    private fun wired(
        maxCapacity: Long = 4096,
        maxBlockedStreams: Long = 0,
    ) = Pair(maxCapacity, maxBlockedStreams)

    private suspend fun roundTrip(
        p: Pair,
        fields: List<QpackHeaderField>,
        streamId: Long,
    ): List<QpackHeaderField> {
        val section = p.encoder.encodeSection(fields, streamId, pool)
        p.pump() // deliver the encoder's inserts to the decoder (and the increments back) before decoding
        val decoded = p.decoder.decodeSection(section, streamId, scratchPool = null)
        p.pump() // deliver the decoder's Section Ack back to the encoder
        return decoded
    }

    @Test
    fun staticOnlyRequestRoundTrips() =
        runTest {
            val p = wired()
            // All static-table hits / common pseudo-headers — no dynamic table needed.
            val fields =
                listOf(
                    QpackHeaderField(":method", "GET"),
                    QpackHeaderField(":scheme", "https"),
                    QpackHeaderField(":path", "/"),
                    QpackHeaderField(":authority", "example.com"),
                )
            assertEquals(fields, roundTrip(p, fields, streamId = 0))
        }

    @Test
    fun repeatedCustomHeaderBecomesDynamicReferenceOnSecondRequest() =
        runTest {
            val p = wired()
            p.encoder.setCapacity(4096)
            val fields = listOf(QpackHeaderField(":method", "GET"), QpackHeaderField("x-custom", "repeated-value"))

            // Request 1: x-custom is new → inserted into the dynamic table, encoded literally this time.
            assertEquals(fields, roundTrip(p, fields, streamId = 0))
            assertEquals(1L, p.decoder.insertCountValue, "the new field was inserted once")
            assertEquals(1L, p.encoder.insertCountValue)

            // Request 2: x-custom is now an acknowledged dynamic entry → referenced, no new insert.
            assertEquals(fields, roundTrip(p, fields, streamId = 4))
            assertEquals(1L, p.decoder.insertCountValue, "request 2 reused the entry — no second insert")
        }

    @Test
    fun manyDistinctHeadersRoundTripAndFillTableWithoutEviction() =
        runTest {
            val p = wired(maxCapacity = 200) // ~5 small entries before it's full
            p.encoder.setCapacity(200)
            for (i in 0 until 10) {
                val fields = listOf(QpackHeaderField("x-h$i", "v$i"))
                assertEquals(fields, roundTrip(p, fields, streamId = i.toLong()))
            }
            // The table filled up and stopped inserting (no eviction), but every request still decoded.
            assertEquals(p.encoder.insertCountValue, p.decoder.insertCountValue)
        }

    @Test
    fun tableChurnsViaEvictionAndReReferencesPostEvictionEntry() =
        runTest {
            // capacity 72 holds exactly two 36-octet entries; a third forces eviction of the oldest.
            val p = wired(maxCapacity = 72)
            p.encoder.setCapacity(72)
            val a = QpackHeaderField("aa", "11")
            val b = QpackHeaderField("bb", "22")
            val c = QpackHeaderField("cc", "33")

            assertEquals(listOf(a), roundTrip(p, listOf(a), streamId = 0)) // insert A (abs 0)
            assertEquals(listOf(b), roundTrip(p, listOf(b), streamId = 4)) // insert B (abs 1) — table now full
            assertEquals(2L, p.encoder.insertCountValue)

            // C is new and the table is full; A (abs 0) is unreferenced, so eviction is safe.
            assertEquals(listOf(c), roundTrip(p, listOf(c), streamId = 8)) // evict A, insert C (abs 2)
            assertEquals(3L, p.encoder.insertCountValue)
            assertEquals(3L, p.decoder.insertCountValue, "decoder evicted + inserted in lockstep")

            // C is now an acknowledged, still-live entry ⇒ referenced (no new insert) and decodes fine.
            assertEquals(listOf(c), roundTrip(p, listOf(c), streamId = 12))
            assertEquals(3L, p.encoder.insertCountValue, "re-reference of a post-eviction entry inserts nothing")

            // A was evicted, so it is unknown again ⇒ treated as brand-new (evicting B this time).
            assertEquals(listOf(a), roundTrip(p, listOf(a), streamId = 16))
            assertEquals(4L, p.encoder.insertCountValue, "evicted entry re-inserted as new")
        }

    @Test
    fun inFlightSectionPreventsEvictionOfItsReferencedEntry() =
        runTest {
            val p = wired(maxCapacity = 72) // two 36-octet entries
            p.encoder.setCapacity(72)
            val a = QpackHeaderField("aa", "11")
            val b = QpackHeaderField("bb", "22")
            val c = QpackHeaderField("cc", "33")

            // Fill the table with A (abs 0) and B (abs 1); full round-trips acknowledge both inserts.
            assertEquals(listOf(a), roundTrip(p, listOf(a), streamId = 0))
            assertEquals(listOf(b), roundTrip(p, listOf(b), streamId = 4))
            assertEquals(2L, p.encoder.insertCountValue)

            // Emit a section on stream 8 that references A (abs 0) and deliver it to the decoder, but
            // HOLD the decoder's Section Acknowledgment — A is now pinned by an in-flight reference.
            val pinning = p.encoder.encodeSection(listOf(a), streamId = 8, pool)
            p.flushEncoderStream()
            assertEquals(listOf(a), p.decoder.decodeSection(pinning, streamId = 8, scratchPool = null))
            // NOTE: deliberately not flushing the decoder stream — the ack stays in flight.

            // C is new and the table is full, but evicting A (the only candidate) is unsafe while the
            // stream-8 section still references it ⇒ the encoder must NOT insert (encodes C literally).
            val held = p.encoder.encodeSection(listOf(c), streamId = 12, pool)
            p.flushEncoderStream()
            assertEquals(2L, p.encoder.insertCountValue, "pinned entry blocks eviction → no insert")
            assertEquals(listOf(c), p.decoder.decodeSection(held, streamId = 12, scratchPool = null))

            // Now deliver the held acknowledgment; A is released and eviction becomes safe.
            p.flushDecoderStream()
            assertEquals(listOf(c), roundTrip(p, listOf(c), streamId = 16)) // evict A, insert C (abs 2)
            assertEquals(3L, p.encoder.insertCountValue, "once unpinned, the entry is evictable")
        }

    @Test
    fun streamCancellationReleasesPinnedEntryForEviction() =
        runTest {
            val p = wired(maxCapacity = 72)
            p.encoder.setCapacity(72)
            val a = QpackHeaderField("aa", "11")
            val b = QpackHeaderField("bb", "22")
            val c = QpackHeaderField("cc", "33")

            assertEquals(listOf(a), roundTrip(p, listOf(a), streamId = 0))
            assertEquals(listOf(b), roundTrip(p, listOf(b), streamId = 4))

            // Pin A via an in-flight section on stream 8 (held, unacked).
            p.encoder.encodeSection(listOf(a), streamId = 8, pool)
            p.flushEncoderStream()
            val blocked = p.encoder.encodeSection(listOf(c), streamId = 12, pool)
            p.flushEncoderStream()
            assertEquals(2L, p.encoder.insertCountValue, "pinned → eviction blocked")
            // Consume the literally-encoded section so the decoder table stays consistent.
            assertEquals(listOf(c), p.decoder.decodeSection(blocked, streamId = 12, scratchPool = null))
            p.flushDecoderStream() // ack for stream 12 (no pin to release)

            // The peer abandons stream 8 → Stream Cancellation releases A's reference.
            p.decoder.cancelStream(8)
            p.flushDecoderStream()

            assertEquals(listOf(c), roundTrip(p, listOf(c), streamId = 16)) // now free to evict A, insert C
            assertEquals(3L, p.encoder.insertCountValue, "cancellation unpinned the entry")
        }

    @Test
    fun blockingEncoderReferencesUnacknowledgedEntryOnFirstUse() =
        runTest {
            // With blocked-stream budget, a brand-new header is inserted AND referenced in the same
            // section — the section's Required Insert Count then exceeds what the decoder has, so the
            // decoder must block until the encoder-stream insert arrives. (Non-blocking would be literal.)
            val p = wired(maxBlockedStreams = 100)
            p.encoder.setCapacity(4096)
            val fields = listOf(QpackHeaderField(":method", "GET"), QpackHeaderField("x-custom", "v1"))

            val section = p.encoder.encodeSection(fields, streamId = 0, pool)
            assertEquals(1L, p.encoder.insertCountValue, "the new field was inserted")

            val decoding = async { p.decoder.decodeSection(section, streamId = 0, scratchPool = null) }
            runCurrent()
            assertFalse(decoding.isCompleted, "decode blocks until the insert arrives — proves a blocking reference")

            p.flushEncoderStream() // deliver the insert → unblocks the decoder
            assertEquals(fields, decoding.await())
        }

    @Test
    fun blockingEncoderReferencesExistingUnacknowledgedEntryWithoutDuplicating() =
        runTest {
            // An entry that is present but still unacknowledged is referenced directly (blocking) rather
            // than inserting a wasteful duplicate, as the non-blocking path would.
            val p = wired(maxBlockedStreams = 100)
            p.encoder.setCapacity(4096)
            val field = QpackHeaderField("x-c", "v")

            // Stream 0: insert + reference x-c (abs 0); deliver the insert and decode, but hold the ack
            // so x-c stays unacknowledged (Known Received Count = 0).
            val s0 = p.encoder.encodeSection(listOf(field), streamId = 0, pool)
            p.flushEncoderStream()
            assertEquals(listOf(field), p.decoder.decodeSection(s0, streamId = 0, scratchPool = null))

            // Stream 4 reuses x-c while it is unacknowledged → referenced, no second insert.
            val s4 = p.encoder.encodeSection(listOf(field), streamId = 4, pool)
            assertEquals(1L, p.encoder.insertCountValue, "referenced the existing entry — no duplicate insert")
            assertEquals(listOf(field), p.decoder.decodeSection(s4, streamId = 4, scratchPool = null))
        }

    @Test
    fun blockedStreamBudgetFallsBackToLiteralWhenExhausted() =
        runTest {
            // Budget of 1: the first new-header stream becomes blocking; a second one, while the first is
            // still unacknowledged, exceeds the budget and must fall back to a non-blocking literal.
            val p = wired(maxBlockedStreams = 1)
            p.encoder.setCapacity(4096)

            val s0 = p.encoder.encodeSection(listOf(QpackHeaderField("x-a", "1")), streamId = 0, pool)
            val s4 = p.encoder.encodeSection(listOf(QpackHeaderField("x-b", "2")), streamId = 4, pool)
            assertEquals(2L, p.encoder.insertCountValue, "both headers inserted for future reuse")

            // s4 was forced literal (budget spent on stream 0): it decodes immediately with NO inserts
            // delivered — a blocking section would have to wait.
            assertEquals(
                listOf(QpackHeaderField("x-b", "2")),
                p.decoder.decodeSection(s4, streamId = 4, scratchPool = null),
                "the over-budget section is non-blocking (literal)",
            )

            // s0 is the one blocking section: it must wait for its insert before it can decode.
            val decoding = async { p.decoder.decodeSection(s0, streamId = 0, scratchPool = null) }
            runCurrent()
            assertFalse(decoding.isCompleted, "the in-budget section blocks until its insert arrives")
            p.flushEncoderStream()
            assertEquals(listOf(QpackHeaderField("x-a", "1")), decoding.await())
        }

    @Test
    fun insertCountIncrementPastInsertsIsDecoderStreamError() =
        runTest {
            val encoder = QpackEncoder(4096) { }
            val e =
                assertFailsWith<Http3StreamException> {
                    encoder.processDecoderInstruction(QpackDecoderInstruction.InsertCountIncrement(5))
                }
            assertEquals(Http3ErrorCode.QPACK_DECODER_STREAM_ERROR, e.errorCode)
        }

    @Test
    fun sectionAckWithNoOutstandingSectionIsDecoderStreamError() =
        runTest {
            val encoder = QpackEncoder(4096) { }
            val e =
                assertFailsWith<Http3StreamException> {
                    encoder.processDecoderInstruction(QpackDecoderInstruction.SectionAck(0))
                }
            assertEquals(Http3ErrorCode.QPACK_DECODER_STREAM_ERROR, e.errorCode)
        }
}
