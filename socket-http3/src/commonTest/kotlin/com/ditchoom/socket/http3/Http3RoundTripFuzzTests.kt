package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.socket.http3.Http3FuzzGenerators.QpackPair
import com.ditchoom.socket.http3.Http3FuzzGenerators.SeededEntropy
import com.ditchoom.socket.http3.Http3FuzzGenerators.assertFrameRoundTrips
import com.ditchoom.socket.http3.Http3FuzzGenerators.assertQpackRoundTrips
import com.ditchoom.socket.http3.Http3FuzzGenerators.frame
import com.ditchoom.socket.http3.Http3FuzzGenerators.headerFields
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 1 (round-trip half) of the H3/QPACK conformance plan, deterministic + every-platform: the seeded
 * twin of the JVM Jazzer target `Http3RoundTripFuzzer`. Where [Http3DecoderInvariantFuzzTests] fuzzes the
 * **decoder** (any bytes ⇒ value-or-typed-error), this fuzzes the **encoder** and the round-trip:
 *
 *  - [fuzz_frameRoundTrip_everyVariant] — a structurally valid [Http3Frame] of every variant must survive
 *    `Http3FrameCodec.encode → decode` byte-for-byte (the wire format is a lossless bijection).
 *  - the QPACK tests — a valid header list must survive the wired encoder→decoder pair field-for-field,
 *    across the static-only, dynamic-table, and small-table-eviction regimes (the dynamic ones churn many
 *    sections through one table and **assert the dynamic machinery actually ran** — see below), plus a
 *    concurrent variant that exercises the decoder's RIC-blocking await, and the HEADERS-frame-wrapped
 *    end-to-end path.
 *
 * The invariant is strictly stronger than the decoder fuzzer's: a valid input must decode back EQUAL and
 * must raise no error at all. Crucially, field-equality alone is NOT enough to prove the dynamic table
 * works — the encoder falls back to literals on every dynamic path, and a literal round-trips equal even
 * with a dead table. So [QpackPair.roundTrip] asserts the encoder and decoder insert counts stay in lock
 * step, and the churn tests assert the table demonstrably inserted (and, under a small table, evicted).
 *
 * A regression is pinned by copying the offending value into a unit test, or — for a Jazzer `byte[]`
 * repro — by adding it to [Http3RoundTripCorpusReplayTests] (which replays raw bytes on every platform).
 */
class Http3RoundTripFuzzTests {
    private fun pool() =
        BufferPool(
            threadingMode = ThreadingMode.SingleThreaded,
            maxPoolSize = 8,
            defaultBufferSize = 256,
            factory = BufferFactory.Default,
        )

    @Test
    fun fuzz_frameRoundTrip_everyVariant() {
        val e = SeededEntropy(0x77_3020_0001)
        repeat(6000) { i ->
            val frame = frame(e)
            assertFrameRoundTrips(frame, "frame#$i $frame")
        }
    }

    @Test
    fun fuzz_qpackRoundTrip_staticOnly() =
        runTest {
            val pool = pool()
            val e = SeededEntropy(0x77_3020_0002)
            repeat(3000) {
                assertQpackRoundTrips(e, pool, capacity = 0, maxBlockedStreams = 0)
            }
        }

    @Test
    fun fuzz_qpackRoundTrip_dynamicTableChurn() =
        runTest {
            // Churn many sections through ONE large table so dynamic inserts + references actually happen
            // (a fresh pair per section would rarely build dynamic state). roundTrip already asserts the two
            // tables stay in lock step; here we additionally prove the dynamic machinery was reached at all.
            val pool = pool()
            val e = SeededEntropy(0x77_3020_0003)
            val pair = QpackPair(capacity = 4096, maxBlockedStreams = 16)
            pair.encoder.setCapacity(4096)
            pair.pump()
            repeat(3000) { i ->
                val fields = headerFields(e)
                val decoded = pair.roundTrip(fields, streamId = (i * 4).toLong(), pool = pool)
                assertEquals(fields, decoded, "dynamicChurn#$i")
            }
            assertTrue(pair.encoder.insertCountValue > 0, "dynamic table never inserted — encoder fell back to all-literals")
        }

    @Test
    fun fuzz_qpackRoundTrip_smallTableEvictionChurn() =
        runTest {
            // One pair, many sections through a deliberately small (256-byte ⇒ 8-entry) table: forces
            // inserts, references, AND eviction of drained-then-acked entries. The monotonic insert count
            // climbing past the table's 8-entry ceiling is the proof that eviction (not just insertion)
            // happened — the accounting a single-section round-trip never reaches.
            val pool = pool()
            val e = SeededEntropy(0x77_3020_0004)
            val pair = QpackPair(capacity = 256, maxBlockedStreams = 16)
            pair.encoder.setCapacity(256)
            pair.pump()
            repeat(3000) { i ->
                val fields = headerFields(e)
                val decoded = pair.roundTrip(fields, streamId = (i * 4).toLong(), pool = pool)
                assertEquals(fields, decoded, "evictionChurn#$i")
            }
            assertTrue(
                pair.encoder.insertCountValue > 8,
                "insert count ${pair.encoder.insertCountValue} did not exceed the 8-entry table — eviction never happened",
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fuzz_qpackRoundTrip_blockedDecodeResumesOnInsert() =
        runTest {
            // The dynamic-BLOCKED regime the churn tests structurally never reach: a section that references
            // a just-inserted, not-yet-delivered entry. Decoding it BEFORE the insert arrives must suspend
            // on the Required Insert Count, then resume + decode equal once the insert is delivered. A fresh
            // custom field guarantees an insert + a blocking reference (capacity + blocked budget > 0).
            val pool = pool()
            repeat(300) { i ->
                val pair = QpackPair(capacity = 4096, maxBlockedStreams = 16)
                pair.encoder.setCapacity(4096)
                pair.pump()
                val fields = listOf(QpackHeaderField(":method", "GET"), QpackHeaderField("x-block-$i", "value-$i"))
                val streamId = (i * 4).toLong()

                val section = pair.encoder.encodeSection(fields, streamId, pool)
                // Deliberately do NOT pump: the encoder's insert is queued but not yet applied to the decoder.
                val decodeJob = async { pair.decoder.decodeSection(section, streamId, scratchPool = null) }
                runCurrent()
                assertFalse(decodeJob.isCompleted, "decode#$i should block until the referenced insert arrives")
                pair.pump() // deliver the insert → the blocked decode unblocks
                val decoded = decodeJob.await()
                assertEquals(fields, decoded, "blockedDecode#$i")
            }
        }

    @Test
    fun fuzz_headerFrameWrapped_endToEnd() =
        runTest {
            // The real request path: QPACK-encode a section, carry it in a HEADERS frame, push that
            // through the frame codec, then QPACK-decode the recovered section — both codecs composed.
            val pool = pool()
            val e = SeededEntropy(0x77_3020_0005)
            repeat(2000) { i ->
                val fields = headerFields(e)
                val pair = QpackPair(capacity = 4096, maxBlockedStreams = 16)
                pair.encoder.setCapacity(4096)
                pair.pump()

                val section = pair.encoder.encodeSection(fields, streamId = 0, pool = pool)
                pair.pump()
                val frame = Http3Frame.Headers(encodedFieldSection = section)
                val wire = Http3FrameCodec.encode(frame, EncodeContext.Empty, BufferFactory.Default)
                val decodedFrame = Http3FrameCodec.decode(wire, DecodeContext.Empty)
                val recovered = (decodedFrame as Http3Frame.Headers).encodedFieldSection
                val decoded = pair.decoder.decodeSection(recovered, streamId = 0, scratchPool = null)
                pair.pump()
                assertEquals(fields, decoded, "headerFrame#$i")
            }
        }
}
