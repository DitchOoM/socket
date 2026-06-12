package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Deterministic differential fuzzing of the generated [Http3FrameCodec] against the hand-written
 * [HandwrittenHttp3FrameCodec] oracle. Where [Http3FrameCodecDifferentialTests] pins a curated
 * corpus, this drives the same parity checks at scale over three seeded generators:
 *
 *  1. **valid frames** — random variants/field widths must encode byte-identically on both codecs
 *     and decode back equal;
 *  2. **mutated frames** — a valid wire image with random byte flips / truncation / appended
 *     garbage must produce the same *outcome* on both codecs (equal frame + equal consumption,
 *     or both throw);
 *  3. **unstructured bytes** — fully random buffers (half biased to a known type byte) must
 *     produce the same outcome on both codecs.
 *
 * Seeded [Random] keeps every run reproducible on every platform — a failure prints the iteration
 * seed material (hex wire image) needed to pin it as a corpus case in the differential test.
 *
 * The one documented divergence is honored, not papered over: a single-varint frame
 * (GOAWAY / MAX_PUSH_ID / CANCEL_PUSH) with trailing bytes inside its declared length decodes
 * leniently on the oracle but throws on the generated codec (RFC 9114 §7.1 H3_FRAME_ERROR) —
 * the exact-case pin lives in [Http3FrameCodecDifferentialTests].
 */
class Http3FrameCodecFuzzTests {
    private fun buffer(bytes: ByteArray): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b)
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

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun handwrittenEncode(frame: Http3Frame): ByteArray {
        val size = (HandwrittenHttp3FrameCodec.wireSize(frame, EncodeContext.Empty) as WireSize.Exact).bytes
        val buf = BufferFactory.Default.allocate(size)
        HandwrittenHttp3FrameCodec.encode(buf, frame, EncodeContext.Empty)
        buf.resetForRead()
        return buf.toBytes()
    }

    private fun generatedEncode(frame: Http3Frame): ByteArray =
        Http3FrameCodec.encode(frame, EncodeContext.Empty, BufferFactory.Default).toBytes()

    private fun assertFramesEqual(
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

    /** The documented strictness divergence: oracle-lenient, generated-throws (RFC 9114 §7.1). */
    private fun Http3Frame.isSingleVarintFrame(): Boolean =
        this is Http3Frame.GoAway || this is Http3Frame.MaxPushId || this is Http3Frame.CancelPush

    /**
     * Whether [wire] is shorter than the frame total its own header declares
     * (type varint + length varint + declared body) — computed directly from
     * the bytes, independent of either codec.
     */
    private fun isTruncated(wire: ByteArray): Boolean {
        if (wire.isEmpty()) return false
        val typeLen = VarIntCodec.lengthFromPrefix(wire[0].toInt() and 0xFF)
        if (wire.size < typeLen + 1) return false
        val lenLen = VarIntCodec.lengthFromPrefix(wire[typeLen].toInt() and 0xFF)
        if (wire.size < typeLen + lenLen) return false
        var length = (wire[typeLen].toInt() and 0x3F).toLong()
        for (i in 1 until lenLen) length = (length shl 8) or (wire[typeLen + i].toLong() and 0xFF)
        return wire.size < typeLen + lenLen + length
    }

    /** The generated truncation guard's failure sites (`@FramedBy` variant arms, `@ForwardCompatible` preserve arm). */
    private fun Throwable?.isFramedGuardFailure(): Boolean =
        (this as? DecodeException)?.fieldPath?.let { it.endsWith("@FramedBy") || it.endsWith("@ForwardCompatible") } == true

    /** Decode [wire] on both codecs; equal frame + equal consumption, or both throw. */
    private fun assertOutcomeParity(
        wire: ByteArray,
        label: String,
    ) {
        val oracleBuf = buffer(wire)
        val generatedBuf = buffer(wire)
        val oracle = runCatching { HandwrittenHttp3FrameCodec.decode(oracleBuf, DecodeContext.Empty) }
        val generated = runCatching { Http3FrameCodec.decode(generatedBuf, DecodeContext.Empty) }
        val detail = "$label wire=${wire.toHex()}"
        when {
            oracle.isSuccess && generated.isSuccess -> {
                assertFramesEqual(oracle.getOrThrow(), generated.getOrThrow(), detail)
                assertEquals(oracleBuf.position(), generatedBuf.position(), "consumption for $detail")
            }
            oracle.isFailure && generated.isFailure -> Unit
            // Documented divergence 1, accepted ONLY at its exact failure site: the generated
            // codec's strict @FramedBy bound check on a single-varint frame. A generated failure
            // anywhere else on an oracle-decodable single-varint frame is still a divergence.
            oracle.isSuccess &&
                generated.isFailure &&
                oracle.getOrThrow().isSingleVarintFrame() &&
                (generated.exceptionOrNull() as? DecodeException)?.fieldPath?.endsWith("@FramedBy") == true -> Unit
            // Documented divergence 2: a genuinely truncated frame (verified from the wire bytes,
            // independent of either codec). The generated truncation guard throws on every
            // platform; the oracle is platform-dependent (JVM/native throw → both-fail above; the
            // JS buffer clamps and "succeeds" with a short payload → lands here). Accepted only
            // when the generated failure IS the guard, so nothing else can hide behind it.
            oracle.isSuccess &&
                generated.isFailure &&
                isTruncated(wire) &&
                generated.exceptionOrNull().isFramedGuardFailure() -> Unit
            else ->
                fail(
                    "outcome divergence for $detail: oracle=${oracle.map { it::class.simpleName }} " +
                        "generated=${generated.map { it::class.simpleName }} " +
                        "(${oracle.exceptionOrNull() ?: ""}${generated.exceptionOrNull() ?: ""})",
                )
        }
    }

    // --- generators -----------------------------------------------------------

    private fun Random.randomBytes(size: Int): ByteArray = ByteArray(size) { nextInt(256).toByte() }

    /** A varint value at a random encoded width (1/2/4/8 bytes). */
    private fun Random.randomVarintValue(): Long =
        when (nextInt(4)) {
            0 -> nextLong(0, 64)
            1 -> nextLong(64, 16384)
            2 -> nextLong(16384, 1073741824)
            else -> nextLong(1073741824, 4611686018427387904L)
        }

    private fun Random.randomFrame(): Http3Frame =
        when (nextInt(7)) {
            0 -> Http3Frame.Data(payload = buffer(randomBytes(nextInt(0, 40))))
            1 -> Http3Frame.Headers(encodedFieldSection = buffer(randomBytes(nextInt(0, 40))))
            2 -> Http3Frame.Settings(entries = List(nextInt(0, 5)) { Http3Setting(randomVarintValue(), randomVarintValue()) })
            3 -> Http3Frame.GoAway(id = randomVarintValue())
            4 -> Http3Frame.MaxPushId(pushId = randomVarintValue())
            5 -> Http3Frame.CancelPush(pushId = randomVarintValue())
            else -> Http3Frame.PushPromise(pushId = randomVarintValue(), encodedFieldSection = buffer(randomBytes(nextInt(0, 24))))
        }

    private fun Random.mutate(wire: ByteArray): ByteArray =
        when (nextInt(3)) {
            // Flip one byte anywhere — types, lengths, payload.
            0 -> wire.copyOf().also { if (it.isNotEmpty()) it[nextInt(it.size)] = nextInt(256).toByte() }
            // Truncate at a random point (possibly to empty).
            1 -> wire.copyOfRange(0, nextInt(wire.size + 1))
            // Append trailing garbage after the frame.
            else -> wire + randomBytes(nextInt(1, 5))
        }

    // --- the fuzz passes ------------------------------------------------------

    @Test
    fun fuzz_validFrames_encodeByteIdentical_decodeEqual() {
        val rng = Random(0x5EED_0001)
        repeat(1000) { i ->
            val frame = rng.randomFrame()
            val oracleWire = handwrittenEncode(frame)
            val generatedWire = generatedEncode(frame)
            assertContentEquals(oracleWire, generatedWire, "encode parity #$i for $frame")
            assertOutcomeParity(oracleWire, "valid#$i")
        }
    }

    @Test
    fun fuzz_mutatedFrames_outcomeParity() {
        val rng = Random(0x5EED_0002)
        repeat(2000) { i ->
            val wire = rng.mutate(handwrittenEncode(rng.randomFrame()))
            assertOutcomeParity(wire, "mutated#$i")
        }
    }

    @Test
    fun fuzz_unstructuredBytes_outcomeParity() {
        val rng = Random(0x5EED_0003)
        val knownTypes =
            byteArrayOf(
                Http3FrameType.DATA.toByte(),
                Http3FrameType.HEADERS.toByte(),
                Http3FrameType.CANCEL_PUSH.toByte(),
                Http3FrameType.SETTINGS.toByte(),
                Http3FrameType.PUSH_PROMISE.toByte(),
                Http3FrameType.GOAWAY.toByte(),
                Http3FrameType.MAX_PUSH_ID.toByte(),
            )
        repeat(3000) { i ->
            val wire = rng.randomBytes(rng.nextInt(0, 48))
            // Half the iterations: aim the first byte at a known type so structured decode
            // paths (length bounds, SETTINGS entries, pushId+section splits) get hit hard.
            if (wire.isNotEmpty() && rng.nextBoolean()) {
                wire[0] = knownTypes[rng.nextInt(knownTypes.size)]
            }
            assertOutcomeParity(wire, "unstructured#$i")
        }
    }
}
