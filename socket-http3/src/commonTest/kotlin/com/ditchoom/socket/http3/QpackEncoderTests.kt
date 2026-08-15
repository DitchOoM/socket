package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.socket.quic.QuicStreamId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [QpackEncoder] round-trips against a wired [QpackDecoder] — the encoder's instructions feed the
 * decoder and the decoder's acknowledgments feed back, mirroring the two QPACK uni streams in
 * miniature. This is the integration test for the whole dynamic stack (prefix + table + instructions
 * + both stateful halves).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QpackEncoderTests {
    /**
     * Yields standing in for the suspensions of a real decoder-stream write. Enough to let a parked
     * section decode resume, decode, and reach its acknowledgment while the increment is still in its
     * write — the interleaving that used to corrupt the count. The value is not load-bearing for
     * correctness, only for reaching the interesting schedule; the `decodeInFlightDuringIncrement`
     * assertion below fails loudly if it ever stops doing so.
     */
    private val writeSuspensions = 4

    /** Seeds for the interleaving property test; each picks its own write-suspension and section count. */
    private val interleavingSeeds = 64

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

        /** Log of every encoder-stream instruction emitted, for asserting the encoding strategy. */
        val encoderInstructions = mutableListOf<QpackEncoderInstruction>()
        val encoder =
            QpackEncoder(maxCapacity, maxBlockedStreams) {
                encoderInstructions += it
                encoderToDecoder.addLast(it)
            }
        val decoder = QpackDecoder(maxCapacity, RecordingQpackDecoderStream { decoderToEncoder.addLast(it) })

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
        val section = p.encoder.encodeSection(fields, QuicStreamId(streamId), pool)
        p.pump() // deliver the encoder's inserts to the decoder (and the increments back) before decoding
        val decoded = p.decoder.decodeSection(section, QuicStreamId(streamId), scratchPool = null)
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
            assertEquals(InsertCount(1), p.decoder.insertCountValue, "the new field was inserted once")
            assertEquals(InsertCount(1), p.encoder.insertCountValue)

            // Request 2: x-custom is now an acknowledged dynamic entry → referenced, no new insert.
            assertEquals(fields, roundTrip(p, fields, streamId = 4))
            assertEquals(InsertCount(1), p.decoder.insertCountValue, "request 2 reused the entry — no second insert")
        }

    /**
     * RFC 9204 §2.1.4/§4.4.3 — a Section Acknowledgment *implicitly* acknowledges every insertion up to
     * the acknowledged section's Required Insert Count, so an Insert Count Increment may only cover
     * insertions that ack did **not** already carry. Emitting one per insertion regardless
     * double-counts, and the peer's encoder is required to kill the connection for it
     * (`QPACK_DECODER_STREAM_ERROR`, 0x202 — "increases the Known Received Count beyond what the
     * encoder has sent").
     *
     * Whether it fired was pure scheduling. The decoder's two decoder-stream emissions come from
     * different coroutines — the encoder-stream router ([QpackDecoder.applyEncoderInstruction]) and a
     * per-request decode ([QpackDecoder.decodeSection]) — so both orders were producible:
     * increment-then-ack is harmless, because the ack's jump to the Required Insert Count is then a
     * no-op; ack-then-increment is fatal, because the increment adds on top of a count the ack already
     * advanced. Hence a rare dynamic-QPACK-only flake rather than a hard break —
     * `AndroidHttp3LoopbackTest.productionServerRole_dynamicQpackRoundTrip` (issue #291), whose shipped
     * capture named this violation.
     *
     * The interleaving is **forced on virtual time, not raced for**: `emit` suspends exactly where a
     * real decoder-stream write would, and the decode is parked on the very insertion the increment is
     * about, so it resumes *between* the two emissions. The real [QpackEncoder] is the oracle — replay
     * what the decoder actually put on the wire, in that order, and it raises if we ever acknowledged
     * more insertions than were made.
     */
    @Test
    fun aSectionAckAndItsInsertionsIncrementAreNeverBothCounted() =
        runTest {
            val decoderStream = ArrayDeque<QpackDecoderInstruction>()
            val encoderStream = ArrayDeque<QpackEncoderInstruction>()
            val encoder = QpackEncoder(4096, peerMaxBlockedStreams = 4) { encoderStream.addLast(it) }
            // The yield is where a real write suspends; it is what lets the parked decode resume between
            // the two emissions rather than after both, which is the ordering that used to be fatal.
            // Set while the increment's write is suspended, to prove the decode really was concurrent
            // with it rather than the two having run one after the other (which would pass vacuously).
            var decodeInFlightDuringIncrement = false
            lateinit var decode: Job
            val decoder =
                QpackDecoder(
                    4096,
                    RecordingQpackDecoderStream { instruction ->
                        // Force the losing interleaving: a real decoder-stream write suspends, so let the
                        // increment's write lose the race to the section decode that is about to acknowledge
                        // the very same insertion. Yielding only for the increment picks one of the two
                        // orderings a live connection produces; it does not create an impossible one.
                        if (instruction is QpackDecoderInstruction.InsertCountIncrement) {
                            repeat(writeSuspensions) { yield() }
                            decodeInFlightDuringIncrement = !decode.isCompleted
                        }
                        decoderStream.addLast(instruction)
                    },
                )

            encoder.setCapacity(4096)
            // Blocked streams are permitted, so this section may reference the insert it just made —
            // giving it a Required Insert Count that covers a not-yet-acknowledged insertion.
            val section = encoder.encodeSection(listOf(QpackHeaderField("x-custom", "v")), streamId = QuicStreamId(0L), pool)

            decode = launch { decoder.decodeSection(section, streamId = QuicStreamId(0L), scratchPool = null) }
            runCurrent() // the decode is now parked awaiting the insertion its Required Insert Count names
            val apply =
                launch {
                    while (encoderStream.isNotEmpty()) decoder.applyEncoderInstruction(encoderStream.removeFirst())
                }
            decode.join()
            apply.join()

            assertTrue(
                decoderStream.any { it is QpackDecoderInstruction.SectionAck },
                "vacuous: no Section Acknowledgment was emitted, so nothing implicitly acknowledged anything",
            )
            assertTrue(
                decodeInFlightDuringIncrement,
                "vacuous: the decode had already finished when the increment was written, so the two " +
                    "emissions never overlapped and the ordering this test exists for was not exercised",
            )
            // The oracle. Throws Http3StreamException(QpackInsertCountIncrementPastInserts) if the
            // decoder over-acknowledged; passes only if the two instructions agree on one count.
            for (instruction in decoderStream) encoder.processDecoderInstruction(instruction)
        }

    /**
     * The property behind [aSectionAckAndItsInsertionsIncrementAreNeverBothCounted], over many
     * interleavings instead of the one hand-built schedule: **the peer's encoder must never be told we
     * received more insertions than were made**, whatever order our two decoder-stream instructions
     * reach the wire in.
     *
     * That single test pins one schedule (one insert, one section, one yield count). The defect it
     * covers is an ordering hazard, so the interesting axis is the schedule itself — here each seed
     * varies how long the increment's write suspends, how many sections are in flight, and therefore how
     * the decodes and the encoder-stream drain interleave. The real [QpackEncoder] is the oracle in every
     * case: it raises `QpackInsertCountIncrementPastInserts` the moment the counts disagree.
     *
     * Deterministic despite being randomised — the seed is the loop index and virtual time makes the
     * scheduling reproducible, so a failure names the exact seed to re-run.
     */
    @Test
    fun theDecoderNeverAcknowledgesMoreInsertionsThanItMade_acrossInterleavings() =
        runTest {
            for (seed in 0 until interleavingSeeds) {
                val rnd = Random(seed)
                val yields = rnd.nextInt(0, 6)
                val sectionCount = rnd.nextInt(1, 4)

                val decoderStream = ArrayDeque<QpackDecoderInstruction>()
                val encoderStream = ArrayDeque<QpackEncoderInstruction>()
                val encoder = QpackEncoder(4096, peerMaxBlockedStreams = 8) { encoderStream.addLast(it) }
                val decoder =
                    QpackDecoder(
                        4096,
                        RecordingQpackDecoderStream { instruction ->
                            if (instruction is QpackDecoderInstruction.InsertCountIncrement) repeat(yields) { yield() }
                            decoderStream.addLast(instruction)
                        },
                    )

                encoder.setCapacity(4096)
                // Distinct field values per seed and index, so each section forces its own insertion
                // rather than reusing an entry an earlier one already put in the table.
                val sections =
                    (0 until sectionCount).map { i ->
                        val id = QuicStreamId((i * 4).toLong())
                        id to encoder.encodeSection(listOf(QpackHeaderField("x-$seed-$i", "v$i")), id, pool)
                    }

                // Park every decode on the insertions it needs, then drain the encoder stream underneath
                // them — the shape a live connection has, and the window the two emissions race in.
                val decodes = sections.map { (id, section) -> launch { decoder.decodeSection(section, id, null) } }
                runCurrent()
                val apply =
                    launch {
                        while (encoderStream.isNotEmpty()) decoder.applyEncoderInstruction(encoderStream.removeFirst())
                    }
                decodes.forEach { it.join() }
                apply.join()

                assertTrue(
                    decoderStream.any { it is QpackDecoderInstruction.SectionAck },
                    "seed $seed: vacuous — no Section Acknowledgment was emitted",
                )
                for (instruction in decoderStream) {
                    // Fails with QpackInsertCountIncrementPastInserts if this seed's schedule
                    // double-counted an insertion.
                    encoder.processDecoderInstruction(instruction)
                }
            }
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
            assertEquals(InsertCount(2), p.encoder.insertCountValue)

            // C is new and the table is full; A (abs 0) is unreferenced, so eviction is safe.
            assertEquals(listOf(c), roundTrip(p, listOf(c), streamId = 8)) // evict A, insert C (abs 2)
            assertEquals(InsertCount(3), p.encoder.insertCountValue)
            assertEquals(InsertCount(3), p.decoder.insertCountValue, "decoder evicted + inserted in lockstep")

            // C is now an acknowledged, still-live entry ⇒ referenced (no new insert) and decodes fine.
            assertEquals(listOf(c), roundTrip(p, listOf(c), streamId = 12))
            assertEquals(InsertCount(3), p.encoder.insertCountValue, "re-reference of a post-eviction entry inserts nothing")

            // A was evicted, so it is unknown again ⇒ treated as brand-new (evicting B this time).
            assertEquals(listOf(a), roundTrip(p, listOf(a), streamId = 16))
            assertEquals(InsertCount(4), p.encoder.insertCountValue, "evicted entry re-inserted as new")
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
            assertEquals(InsertCount(2), p.encoder.insertCountValue)

            // Emit a section on stream 8 that references A (abs 0) and deliver it to the decoder, but
            // HOLD the decoder's Section Acknowledgment — A is now pinned by an in-flight reference.
            val pinning = p.encoder.encodeSection(listOf(a), streamId = QuicStreamId(8L), pool)
            p.flushEncoderStream()
            assertEquals(listOf(a), p.decoder.decodeSection(pinning, streamId = QuicStreamId(8L), scratchPool = null))
            // NOTE: deliberately not flushing the decoder stream — the ack stays in flight.

            // C is new and the table is full, but evicting A (the only candidate) is unsafe while the
            // stream-8 section still references it ⇒ the encoder must NOT insert (encodes C literally).
            val held = p.encoder.encodeSection(listOf(c), streamId = QuicStreamId(12L), pool)
            p.flushEncoderStream()
            assertEquals(InsertCount(2), p.encoder.insertCountValue, "pinned entry blocks eviction → no insert")
            assertEquals(listOf(c), p.decoder.decodeSection(held, streamId = QuicStreamId(12L), scratchPool = null))

            // Now deliver the held acknowledgment; A is released and eviction becomes safe.
            p.flushDecoderStream()
            assertEquals(listOf(c), roundTrip(p, listOf(c), streamId = 16)) // evict A, insert C (abs 2)
            assertEquals(InsertCount(3), p.encoder.insertCountValue, "once unpinned, the entry is evictable")
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
            p.encoder.encodeSection(listOf(a), streamId = QuicStreamId(8L), pool)
            p.flushEncoderStream()
            val blocked = p.encoder.encodeSection(listOf(c), streamId = QuicStreamId(12L), pool)
            p.flushEncoderStream()
            assertEquals(InsertCount(2), p.encoder.insertCountValue, "pinned → eviction blocked")
            // Consume the literally-encoded section so the decoder table stays consistent.
            assertEquals(listOf(c), p.decoder.decodeSection(blocked, streamId = QuicStreamId(12L), scratchPool = null))
            p.flushDecoderStream() // ack for stream 12 (no pin to release)

            // The peer abandons stream 8 → Stream Cancellation releases A's reference.
            p.decoder.cancelStream(QuicStreamId(8L))
            p.flushDecoderStream()

            assertEquals(listOf(c), roundTrip(p, listOf(c), streamId = 16)) // now free to evict A, insert C
            assertEquals(InsertCount(3), p.encoder.insertCountValue, "cancellation unpinned the entry")
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

            val section = p.encoder.encodeSection(fields, streamId = QuicStreamId(0L), pool)
            assertEquals(InsertCount(1), p.encoder.insertCountValue, "the new field was inserted")

            val decoding = async { p.decoder.decodeSection(section, streamId = QuicStreamId(0L), scratchPool = null) }
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
            val s0 = p.encoder.encodeSection(listOf(field), streamId = QuicStreamId(0L), pool)
            p.flushEncoderStream()
            assertEquals(listOf(field), p.decoder.decodeSection(s0, streamId = QuicStreamId(0L), scratchPool = null))

            // Stream 4 reuses x-c while it is unacknowledged → referenced, no second insert.
            val s4 = p.encoder.encodeSection(listOf(field), streamId = QuicStreamId(4L), pool)
            assertEquals(InsertCount(1), p.encoder.insertCountValue, "referenced the existing entry — no duplicate insert")
            assertEquals(listOf(field), p.decoder.decodeSection(s4, streamId = QuicStreamId(4L), scratchPool = null))
        }

    @Test
    fun blockedStreamBudgetFallsBackToLiteralWhenExhausted() =
        runTest {
            // Budget of 1: the first new-header stream becomes blocking; a second one, while the first is
            // still unacknowledged, exceeds the budget and must fall back to a non-blocking literal.
            val p = wired(maxBlockedStreams = 1)
            p.encoder.setCapacity(4096)

            val s0 = p.encoder.encodeSection(listOf(QpackHeaderField("x-a", "1")), streamId = QuicStreamId(0L), pool)
            val s4 = p.encoder.encodeSection(listOf(QpackHeaderField("x-b", "2")), streamId = QuicStreamId(4L), pool)
            assertEquals(InsertCount(2), p.encoder.insertCountValue, "both headers inserted for future reuse")

            // s4 was forced literal (budget spent on stream 0): it decodes immediately with NO inserts
            // delivered — a blocking section would have to wait.
            assertEquals(
                listOf(QpackHeaderField("x-b", "2")),
                p.decoder.decodeSection(s4, streamId = QuicStreamId(4L), scratchPool = null),
                "the over-budget section is non-blocking (literal)",
            )

            // s0 is the one blocking section: it must wait for its insert before it can decode.
            val decoding = async { p.decoder.decodeSection(s0, streamId = QuicStreamId(0L), scratchPool = null) }
            runCurrent()
            assertFalse(decoding.isCompleted, "the in-budget section blocks until its insert arrives")
            p.flushEncoderStream()
            assertEquals(listOf(QpackHeaderField("x-a", "1")), decoding.await())
        }

    @Test
    fun drainingEntryIsRefreshedViaDuplicateUnderPressure() =
        runTest {
            // capacity 512 ⇒ draining reserve = 64 octets → only the single oldest 50-octet entry drains.
            val p = wired(maxCapacity = 512, maxBlockedStreams = 100)
            p.encoder.setCapacity(512)

            fun f(i: Int) = QpackHeaderField("name-${i.toString().padStart(4, '0')}", "val-${i.toString().padStart(5, '0')}") // 50 octets

            // Fill: 10 * 50 = 500 ≤ 512; an 11th 50-octet entry would need eviction (pressure). Ack all.
            for (i in 0 until 10) assertEquals(listOf(f(i)), roundTrip(p, listOf(f(i)), streamId = (i * 4).toLong()))
            assertEquals(InsertCount(10), p.encoder.insertCountValue)
            p.encoderInstructions.clear()

            // Re-reference the oldest entry (abs 0): draining + table full ⇒ refreshed via a Duplicate
            // (a cheap encoder-stream index, no value resend) rather than pinned or re-inserted literally.
            assertEquals(listOf(f(0)), roundTrip(p, listOf(f(0)), streamId = 100))
            assertEquals(InsertCount(11), p.encoder.insertCountValue, "the draining entry was duplicated (a fresh insert)")
            assertEquals(InsertCount(11), p.decoder.insertCountValue, "decoder applied the Duplicate in lockstep")
            assertEquals(
                1,
                p.encoderInstructions.count { it is QpackEncoderInstruction.Duplicate },
                "refreshed via a Duplicate, not a literal re-insert",
            )
        }

    @Test
    fun drainingDuplicateSkippedWhenTableHasRoom() =
        runTest {
            // A near-empty table has no eviction pressure, so even a positionally-draining entry is
            // referenced directly — no wasteful Duplicate.
            val p = wired(maxCapacity = 4096, maxBlockedStreams = 100)
            p.encoder.setCapacity(4096)
            val f = QpackHeaderField("x", "y")

            assertEquals(listOf(f), roundTrip(p, listOf(f), streamId = 0))
            p.encoderInstructions.clear()

            assertEquals(listOf(f), roundTrip(p, listOf(f), streamId = 4))
            assertEquals(InsertCount(1), p.encoder.insertCountValue, "no duplicate — the table has room")
            assertEquals(0, p.encoderInstructions.count { it is QpackEncoderInstruction.Duplicate })
        }

    @Test
    fun insertCountIncrementPastInsertsIsDecoderStreamError() =
        runTest {
            val encoder = QpackEncoder(4096) { }
            val e =
                assertFailsWith<Http3StreamException> {
                    encoder.processDecoderInstruction(QpackDecoderInstruction.InsertCountIncrement(InsertCountDelta(5)))
                }
            assertEquals(Http3ErrorCode.QPACK_DECODER_STREAM_ERROR, e.errorCode)
        }

    @Test
    fun sectionAckWithNoOutstandingSectionIsDecoderStreamError() =
        runTest {
            val encoder = QpackEncoder(4096) { }
            val e =
                assertFailsWith<Http3StreamException> {
                    encoder.processDecoderInstruction(QpackDecoderInstruction.SectionAck(QuicStreamId(0L)))
                }
            assertEquals(Http3ErrorCode.QPACK_DECODER_STREAM_ERROR, e.errorCode)
        }
}
