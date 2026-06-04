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
