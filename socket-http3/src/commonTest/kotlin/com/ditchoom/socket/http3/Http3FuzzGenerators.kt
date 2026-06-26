package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Structured generators + round-trip oracles shared by the deterministic, every-platform
 * [Http3RoundTripFuzzTests] (seeded [Random]) and the JVM Jazzer target `Http3RoundTripFuzzer`
 * (coverage-guided, [ByteEntropy] over the driver's `byte[]`).
 *
 * Where [Http3DecoderInvariantFuzzTests] / `Http3CodecFuzzer` throw arbitrary bytes at the **decoder**
 * and assert the only failure is a typed [Http3StreamException], these check the *other* direction and
 * the *encoder*: a structurally **valid** value built from an [Entropy] stream must survive
 * `encode → decode` **byte-for-byte** (frame codec) or **field-for-field** (the QPACK encoder/decoder
 * pair). The invariant here is stronger than "no untyped crash": a valid input must decode back equal,
 * and must NOT produce an error at all. Any mismatch — or any thrown error — is a real defect.
 *
 * Both surfaces drive the *same* generators through [replay], which both the JVM Jazzer target and the
 * every-platform [Http3RoundTripCorpusReplayTests] call. So a Jazzer crash repro (a raw `byte[]`) is
 * replayed identically on jvm/js/native/apple by dropping it into that corpus — the round-trip analogue
 * of how `Http3ConformanceCorpusTests` pins decoder crashers. (The seeded [Http3RoundTripFuzzTests] are
 * a *separate*, `Long`-seeded scale check; they don't consume the byte corpus.)
 */
object Http3FuzzGenerators {
    // ---- Entropy: a deterministic source of generation choices --------------------------------------

    /** A deterministic source of generation decisions, backed either by a seeded RNG or by raw fuzz bytes. */
    interface Entropy {
        /** A value in `0 until bound` (0 when [bound] <= 0). */
        fun int(bound: Int): Int

        fun bool(): Boolean

        /** A raw 64-bit value; callers fold it into the range/shape they need. */
        fun rawLong(): Long
    }

    /** Seeded-RNG entropy — the every-platform deterministic path. */
    class SeededEntropy(
        seed: Long,
    ) : Entropy {
        private val rng = Random(seed)

        override fun int(bound: Int): Int = if (bound <= 0) 0 else rng.nextInt(bound)

        override fun bool(): Boolean = rng.nextBoolean()

        override fun rawLong(): Long = rng.nextLong()
    }

    /**
     * Entropy drawn from a fixed `byte[]` — the Jazzer driver path. Reads big-endian; once the bytes are
     * exhausted it yields a deterministic stream of zeros so generation always terminates (a short input
     * just makes small/empty values).
     */
    class ByteEntropy(
        private val data: ByteArray,
    ) : Entropy {
        private var pos = 0

        private fun nextByte(): Int {
            val b = if (pos < data.size) data[pos].toInt() and 0xFF else 0
            pos++
            return b
        }

        override fun int(bound: Int): Int {
            if (bound <= 0) return 0
            var v = 0
            repeat(4) { v = (v shl 8) or nextByte() }
            return ((v % bound) + bound) % bound
        }

        override fun bool(): Boolean = nextByte() and 1 == 1

        override fun rawLong(): Long {
            var v = 0L
            repeat(8) { v = (v shl 8) or nextByte().toLong() }
            return v
        }
    }

    // ---- Derived primitives -------------------------------------------------------------------------

    /** Folds a raw 64-bit value into a varint-legal `[0, 2^62-1]`, biased across the four widths. */
    private fun varIntValue(e: Entropy): Long {
        val r = e.rawLong()
        return when ((r ushr 62).toInt() and 0x3) {
            0 -> r and 0x3F // 1-byte: 0..63
            1 -> r and 0x3FFF // 2-byte
            2 -> r and 0x3FFF_FFFF // 4-byte
            else -> r and ((1L shl 62) - 1) // full 62-bit
        }
    }

    private fun asciiString(
        e: Entropy,
        maxLen: Int,
        alphabet: String,
    ): String =
        buildString {
            repeat(e.int(maxLen + 1)) { append(alphabet[e.int(alphabet.length)]) }
        }

    private fun bytes(
        e: Entropy,
        maxLen: Int,
    ): ByteArray = ByteArray(e.int(maxLen + 1)) { e.int(256).toByte() }

    private fun buffer(source: ByteArray): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(source.size.coerceAtLeast(1))
        for (b in source) buf.writeByte(b)
        buf.resetForRead()
        return buf
    }

    private fun ReadBuffer.toBytes(): ByteArray {
        val saved = position()
        val out = ByteArray(remaining())
        for (i in out.indices) out[i] = readByte()
        position(saved)
        return out
    }

    /** Lowercase hex of [this] — repro material for a [replay] failure (see [withDiffDebug]). */
    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    // ---- HTTP/3 frame round-trip --------------------------------------------------------------------

    // Modeled frame types: any of these as an Unknown.type would re-decode as the known variant, so the
    // GREASE-type generator stays clear of them (and of the reserved-HTTP/2 ids the codec rejects).
    private val KNOWN_FRAME_TYPES = setOf(0x00L, 0x01L, 0x03L, 0x04L, 0x05L, 0x07L, 0x0dL)

    /** A frame type for [Http3Frame.Unknown]: always >= 0x21, varint-legal, never a modeled type. */
    private fun greaseType(e: Entropy): Long {
        val base = varIntValue(e)
        val t = 0x21L + (base and ((1L shl 62) - 1 - 0x21L))
        check(t >= 0x21L && t !in KNOWN_FRAME_TYPES)
        return t
    }

    /** Build a structurally valid [Http3Frame] from [e] — every variant, across varint widths. */
    fun frame(e: Entropy): Http3Frame =
        when (e.int(8)) {
            0 -> Http3Frame.Data(buffer(bytes(e, 512)))
            1 -> Http3Frame.Headers(buffer(bytes(e, 512)))
            2 -> Http3Frame.Settings((0 until e.int(8)).map { Http3Setting(varIntValue(e), varIntValue(e)) })
            3 -> Http3Frame.GoAway(varIntValue(e))
            4 -> Http3Frame.MaxPushId(varIntValue(e))
            5 -> Http3Frame.CancelPush(varIntValue(e))
            6 -> Http3Frame.PushPromise(varIntValue(e), buffer(bytes(e, 256)))
            else -> Http3Frame.Unknown(greaseType(e), buffer(bytes(e, 256)))
        }

    /** Field-level equality with payload/section [ReadBuffer]s compared by content (mirrors the differential test). */
    fun assertFramesEqual(
        expected: Http3Frame,
        actual: Http3Frame,
        label: String,
    ) {
        assertEquals(expected::class, actual::class, "variant for $label")
        when (expected) {
            is Http3Frame.Data ->
                assertContentEquals(expected.payload.toBytes(), (actual as Http3Frame.Data).payload.toBytes(), label)
            is Http3Frame.Headers ->
                assertContentEquals(
                    expected.encodedFieldSection.toBytes(),
                    (actual as Http3Frame.Headers).encodedFieldSection.toBytes(),
                    label,
                )
            is Http3Frame.Settings -> assertEquals(expected.entries, (actual as Http3Frame.Settings).entries, label)
            is Http3Frame.GoAway -> assertEquals(expected.id, (actual as Http3Frame.GoAway).id, label)
            is Http3Frame.MaxPushId -> assertEquals(expected.pushId, (actual as Http3Frame.MaxPushId).pushId, label)
            is Http3Frame.CancelPush -> assertEquals(expected.pushId, (actual as Http3Frame.CancelPush).pushId, label)
            is Http3Frame.PushPromise -> {
                actual as Http3Frame.PushPromise
                assertEquals(expected.pushId, actual.pushId, label)
                assertContentEquals(expected.encodedFieldSection.toBytes(), actual.encodedFieldSection.toBytes(), label)
            }
            is Http3Frame.Unknown -> {
                actual as Http3Frame.Unknown
                assertEquals(expected.type, actual.type, label)
                assertContentEquals(expected.payload.toBytes(), actual.payload.toBytes(), label)
            }
        }
    }

    /** `decode(encode(frame))` must equal [frame] (the wire format is a lossless bijection on valid frames). */
    fun assertFrameRoundTrips(
        frame: Http3Frame,
        label: String = frame.toString(),
    ) {
        val encoded = Http3FrameCodec.encode(frame, EncodeContext.Empty, BufferFactory.Default)
        val decoded = Http3FrameCodec.decode(encoded, DecodeContext.Empty)
        assertFramesEqual(frame, decoded, label)
    }

    // ---- QPACK header-list round-trip ---------------------------------------------------------------

    private const val NAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789-"

    // Printable ASCII (0x20..0x7e): always valid UTF-8, so the decoded String round-trips on JS/wasm too,
    // where readString rejects non-UTF-8 (JVM/native are lenient). Still exercises the Huffman + literal paths.
    private val VALUE_ALPHABET = (0x20..0x7e).joinToString("") { it.toChar().toString() }

    /** One header field — sometimes a verbatim static-table entry (static-indexed path), else a custom `x-` field. */
    fun headerField(e: Entropy): QpackHeaderField =
        if (e.int(4) == 0) {
            QpackStaticTable.entry(e.int(QpackStaticTable.size))
        } else {
            QpackHeaderField("x-" + asciiString(e, 12, NAME_ALPHABET), asciiString(e, 40, VALUE_ALPHABET))
        }

    /** A 0..7-field header list. */
    fun headerFields(e: Entropy): List<QpackHeaderField> = (0 until e.int(8)).map { headerField(e) }

    /**
     * A wired QPACK encoder+decoder, mirroring the two QPACK uni streams: the encoder's instructions are
     * queued to the decoder and the decoder's acks queued back, flushed by [pump]. Identical in spirit to
     * `QpackEncoderTests.Pair`, lifted here so both fuzzers can churn many sections through one table.
     */
    class QpackPair(
        capacity: Long,
        maxBlockedStreams: Long,
    ) {
        private val encoderToDecoder = ArrayDeque<QpackEncoderInstruction>()
        private val decoderToEncoder = ArrayDeque<QpackDecoderInstruction>()
        val encoder = QpackEncoder(capacity, maxBlockedStreams) { encoderToDecoder.addLast(it) }
        val decoder = QpackDecoder(capacity) { decoderToEncoder.addLast(it) }

        suspend fun pump() {
            while (encoderToDecoder.isNotEmpty() || decoderToEncoder.isNotEmpty()) {
                while (encoderToDecoder.isNotEmpty()) decoder.applyEncoderInstruction(encoderToDecoder.removeFirst())
                while (decoderToEncoder.isNotEmpty()) encoder.processDecoderInstruction(decoderToEncoder.removeFirst())
            }
        }

        /**
         * Encode [fields] for [streamId], deliver the inserts, decode, and deliver the ack — all queues
         * drained before the decode, so the decoder never blocks on a missing insert (no hang). Returns
         * the decoded list, which MUST equal [fields].
         */
        suspend fun roundTrip(
            fields: List<QpackHeaderField>,
            streamId: Long,
            pool: BufferPool,
        ): List<QpackHeaderField> {
            val section = encoder.encodeSection(fields, streamId, pool)
            pump()
            val decoded = decoder.decodeSection(section, streamId, scratchPool = null)
            pump()
            // Both tables must have applied the exact same inserts/duplicates — a divergence means the
            // encoder-stream instructions and the decoder's replay of them disagree, which a field-equality
            // check alone cannot see (the encoder's universal literal fallback round-trips equal even with a
            // dead dynamic table). This is the load-bearing dynamic-machinery assertion (review finding #1).
            assertEquals(encoder.insertCountValue, decoder.insertCountValue, "encoder/decoder insert-count divergence")
            return decoded
        }
    }

    /**
     * Single-shot QPACK round-trip on a fresh pair (raises [capacity] first if dynamic). Asserts the
     * decoded fields equal [fields] and returns them.
     */
    suspend fun assertQpackRoundTrips(
        e: Entropy,
        pool: BufferPool,
        capacity: Long,
        maxBlockedStreams: Long,
    ) {
        val fields = headerFields(e)
        val pair = QpackPair(capacity, maxBlockedStreams)
        if (capacity > 0) {
            pair.encoder.setCapacity(capacity)
            pair.pump()
        }
        val decoded = pair.roundTrip(fields, streamId = 0, pool = pool)
        assertEquals(fields, decoded, "qpack round-trip")
    }

    // ---- Shared replay entry point ------------------------------------------------------------------

    /**
     * Drive the round-trip from a single `byte[]` — the exact dispatch the JVM Jazzer target runs and the
     * every-platform corpus-replay test re-runs, so a recorded crash repro reproduces identically on all
     * platforms. The first entropy bit picks the surface; both assert their value round-trips equal, and
     * any thrown error (mismatch, stray exception) is a real defect for the caller to surface.
     */
    suspend fun replay(data: ByteArray) {
        if (data.isEmpty()) return
        // On any failure, print the raw driver bytes — the only repro material for this case (the corpus
        // replay test runs these on every platform; a Jazzer-recorded crasher prints the same hex). The
        // seeded scale fuzzers that don't carry their own per-case label inherit this when they go through
        // replay(). See [withDiffDebug].
        withDiffDebug("roundTrip-replay", { "wire=${data.toHex()}" }) {
            val e = ByteEntropy(data)
            if (e.bool()) {
                assertFrameRoundTrips(frame(e))
            } else {
                val pool =
                    BufferPool(
                        threadingMode = ThreadingMode.SingleThreaded,
                        maxPoolSize = 8,
                        defaultBufferSize = 256,
                        factory = BufferFactory.Default,
                    )
                // Vary the table regime from the input so the dynamic insert/evict accounting is reachable.
                val capacity = longArrayOf(0L, 256L, 4096L)[e.int(3)]
                val blocked = if (capacity == 0L) 0L else 16L
                assertQpackRoundTrips(e, pool, capacity, blocked)
            }
        }
    }

    // ---- Decoder entry-point harness (shared with the native decoder fuzzers) -----------------------

    /** A read-only [ByteStream] yielding [source]'s bytes in one chunk, then End. */
    private class OneShotByteStream(
        private val source: ReadBuffer,
    ) : ByteStream {
        private var delivered = false
        override val isOpen: Boolean get() = !delivered
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(5.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(5.seconds)

        override suspend fun read(deadline: Duration): ReadResult {
            if (delivered) return ReadResult.End
            delivered = true
            return ReadResult.Data(source)
        }

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten = throw UnsupportedOperationException("read-only fuzz stream")

        override suspend fun close() = Unit
    }

    private fun decoderPool() =
        BufferPool(threadingMode = ThreadingMode.SingleThreaded, maxPoolSize = 8, defaultBufferSize = 256, factory = BufferFactory.Default)

    /** A typed [Http3StreamException] is the expected outcome for malformed input; everything else propagates. */
    private inline fun tolerate(block: () -> Unit) {
        try {
            block()
        } catch (e: Http3StreamException) {
            // Expected: the codec rejected malformed input with a typed error.
        }
    }

    /**
     * Run [wire] through all four hand-rolled decoder entry points (the frame reassembler, the QPACK
     * field-section decoder on a capacity-0 decoder so a non-zero RIC is rejected rather than blocking, and
     * the QPACK encoder/decoder instruction readers). Each entry point must either return or throw a typed
     * [Http3StreamException]; any other [Throwable] (a raw underflow, IOOBE, NPE, hang, …) propagates to the
     * caller as the bug. This is the every-platform — crucially **including Kotlin/Native** — decoder
     * invariant harness shared by [Http3DecoderInvariantFuzzTests]-style fuzzers and the corpus replay.
     */
    suspend fun runDecoderEntryPoints(wire: ByteArray) {
        tolerate {
            val reader = Http3StreamReader(OneShotByteStream(buffer(wire)), StreamProcessor.create(decoderPool(), ByteOrder.BIG_ENDIAN))
            while (reader.nextFrame() != null) Unit
        }
        tolerate {
            QpackDecoder(maxCapacity = 0) {}.decodeSection(buffer(wire), streamId = 0, scratchPool = null)
        }
        tolerate {
            val reader = QpackInstructionReader.encoder(OneShotByteStream(buffer(wire)), decoderPool())
            while (reader.next() != null) Unit
        }
        tolerate {
            val reader = QpackInstructionReader.decoder(OneShotByteStream(buffer(wire)), decoderPool())
            while (reader.next() != null) Unit
        }
    }
}
