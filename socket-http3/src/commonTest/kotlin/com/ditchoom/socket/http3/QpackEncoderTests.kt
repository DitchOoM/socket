package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [QpackEncoder] round-trips against a wired [QpackDecoder] — the encoder's instructions feed the
 * decoder and the decoder's acknowledgments feed back, mirroring the two QPACK uni streams in
 * miniature. This is the integration test for the whole dynamic stack (prefix + table + instructions
 * + both stateful halves).
 */
class QpackEncoderTests {
    private val pool = BufferPool(threadingMode = ThreadingMode.SingleThreaded, factory = BufferFactory.Default)

    /**
     * A wired encoder+decoder. Instructions are *queued* rather than delivered synchronously — in a
     * real connection they cross separate QPACK uni streams, so the encoder never re-enters itself
     * while encoding. [pump] flushes both queues (and the increments they cascade) between operations.
     */
    private inner class Pair(
        maxCapacity: Long,
    ) {
        private val encoderToDecoder = ArrayDeque<QpackEncoderInstruction>() // our encoder stream → peer decoder
        private val decoderToEncoder = ArrayDeque<QpackDecoderInstruction>() // peer decoder stream → our encoder
        val encoder = QpackEncoder(maxCapacity) { encoderToDecoder.addLast(it) }
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

    private fun wired(maxCapacity: Long = 4096) = Pair(maxCapacity)

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
