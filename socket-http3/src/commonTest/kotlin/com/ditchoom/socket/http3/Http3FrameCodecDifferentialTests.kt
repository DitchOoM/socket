package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

/**
 * Differential test: the KSP-generated [Http3FrameCodec] against the
 * hand-written [HandwrittenHttp3FrameCodec] (the interop-proven oracle —
 * Cloudflare/Google GET, aioquic docker). The wire format MUST NOT change:
 *
 *  - **encode parity** — byte-identical output for every corpus frame;
 *  - **decode parity** — equal variant + field values (payload compared by
 *    bytes) for every corpus wire image, including unknown/GREASE types at
 *    all four varint widths and non-minimally-encoded type varints;
 *  - **peek parity** — identical `peekFrameSize` verdicts at every prefix
 *    length while drip-feeding one byte at a time;
 *  - **consumption parity** — both decoders leave the buffer position at the
 *    frame end, so back-to-back frames parse identically.
 *
 * One **documented divergence** (pinned below, not papered over): a
 * single-varint frame (GOAWAY / MAX_PUSH_ID / CANCEL_PUSH) whose declared
 * length exceeds its varint — the hand-written codec silently skipped the
 * trailing bytes; the generated codec's strict bound check throws, which is
 * the RFC 9114 §7.1 H3_FRAME_ERROR behavior ("a frame payload that contains
 * additional bytes after the identified fields ... MUST be treated as a
 * connection error of type H3_FRAME_ERROR").
 */
class Http3FrameCodecDifferentialTests {
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

    private fun varint(value: Long): ByteArray {
        val buf = BufferFactory.Default.allocate(8)
        VarIntCodec.encode(buf, value, EncodeContext.Empty)
        buf.resetForRead()
        return buf.toBytes()
    }

    private fun payloadBytes(size: Int): ByteArray = ByteArray(size) { (it and 0x7F).toByte() }

    private fun handwrittenEncode(frame: Http3Frame): ByteArray {
        val size = (HandwrittenHttp3FrameCodec.wireSize(frame, EncodeContext.Empty) as WireSize.Exact).bytes
        val buf = BufferFactory.Default.allocate(size)
        HandwrittenHttp3FrameCodec.encode(buf, frame, EncodeContext.Empty)
        buf.resetForRead()
        return buf.toBytes()
    }

    private fun generatedEncode(frame: Http3Frame): ByteArray =
        Http3FrameCodec.encode(frame, EncodeContext.Empty, BufferFactory.Default).toBytes()

    /** Field-level equality with payload [ReadBuffer]s compared by content. */
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

    /** Frames the corpus constructs directly (encode-parity side). */
    private fun encodeCorpus(): List<Http3Frame> =
        listOf(
            Http3Frame.Data(payload = buffer(payloadBytes(0))),
            Http3Frame.Data(payload = buffer(payloadBytes(3))),
            Http3Frame.Data(payload = buffer(payloadBytes(200))), // 2-byte length varint
            Http3Frame.Headers(encodedFieldSection = buffer(payloadBytes(5))),
            Http3Frame.Settings(entries = emptyList()),
            Http3Frame.Settings(
                entries =
                    listOf(
                        Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 4096),
                        Http3Setting(Http3SettingId.H3_DATAGRAM, 1),
                        Http3Setting(Http3SettingId.WEBTRANSPORT_MAX_SESSIONS, 8), // 8-byte id varint
                        Http3Setting(Http3SettingId.ENABLE_WEBTRANSPORT, 1),
                    ),
            ),
            Http3Frame.GoAway(0),
            Http3Frame.GoAway(12345), // 2-byte varint
            Http3Frame.GoAway(1L shl 40), // 8-byte varint
            Http3Frame.MaxPushId(77),
            Http3Frame.CancelPush(3),
            Http3Frame.PushPromise(pushId = 7, encodedFieldSection = buffer(payloadBytes(9))),
            Http3Frame.PushPromise(pushId = 1L shl 20, encodedFieldSection = buffer(payloadBytes(0))),
        )

    /** Raw wire images the corpus decodes (decode-parity side, incl. unknown types). */
    private fun wireCorpus(): List<Pair<String, ByteArray>> {
        val images = mutableListOf<Pair<String, ByteArray>>()
        for (frame in encodeCorpus()) {
            images += frame.toString() to handwrittenEncode(frame)
        }
        // Unknown/GREASE types across all four varint type widths.
        for (type in longArrayOf(0x21, 0x1000, 0x123456, 0x123456789AL)) {
            images += "Unknown($type)" to (varint(type) + varint(6) + payloadBytes(6))
        }
        images += "Unknown(0x21, empty)" to (varint(0x21) + varint(0))
        // Non-minimal type varint: 2-byte encoding of DATA (0x40 0x00). Both
        // decoders must accept it (RFC 9000 §16) and agree on the value.
        images += "non-minimal DATA type" to (byteArrayOf(0x40, 0x00) + varint(2) + payloadBytes(2))
        return images
    }

    @Test
    fun encodeParity_byteIdentical() {
        for (frame in encodeCorpus()) {
            assertContentEquals(handwrittenEncode(frame), generatedEncode(frame), "encode parity for $frame")
        }
    }

    @Test
    fun decodeParity_everyWireImage() {
        for ((label, wire) in wireCorpus()) {
            val oracle = HandwrittenHttp3FrameCodec.decode(buffer(wire), DecodeContext.Empty)
            val generated = Http3FrameCodec.decode(buffer(wire), DecodeContext.Empty)
            assertFramesEqual(oracle, generated, label)
        }
    }

    @Test
    fun consumptionParity_positionLandsAtFrameEndForBackToBackFrames() {
        val first = handwrittenEncode(Http3Frame.Data(payload = buffer(payloadBytes(4))))
        val second = handwrittenEncode(Http3Frame.GoAway(9))
        val third = varint(0x21) + varint(3) + payloadBytes(3) // unknown between frames

        for (codecName in listOf("handwritten", "generated")) {
            val buf = buffer(first + third + second)

            fun next(): Http3Frame =
                if (codecName == "handwritten") {
                    HandwrittenHttp3FrameCodec.decode(buf, DecodeContext.Empty)
                } else {
                    Http3FrameCodec.decode(buf, DecodeContext.Empty)
                }
            assertIs<Http3Frame.Data>(next(), codecName)
            assertIs<Http3Frame.Unknown>(next(), codecName)
            assertEquals(9, assertIs<Http3Frame.GoAway>(next(), codecName).id, codecName)
            assertEquals(0, buf.remaining(), "$codecName consumed all three frames exactly")
        }
    }

    @Test
    fun unknownFramesReEncodeByteIdentically_onBothCodecs() {
        for (type in longArrayOf(0x21, 0x1000, 0x123456, 0x123456789AL)) {
            val wire = varint(type) + varint(4) + payloadBytes(4)
            val viaOracle = HandwrittenHttp3FrameCodec.decode(buffer(wire), DecodeContext.Empty)
            val viaGenerated = Http3FrameCodec.decode(buffer(wire), DecodeContext.Empty)
            assertContentEquals(wire, handwrittenEncode(viaOracle), "oracle re-encode for $type")
            assertContentEquals(wire, generatedEncode(viaGenerated), "generated re-encode for $type")
        }
    }

    @Test
    fun oversizedTypeDecodesAsUnknownOnBothCodecs() {
        // (2^32).toInt() == 0 == DATA: truncation must not alias the type.
        val type = 1L shl 32
        val wire = varint(type) + varint(2) + payloadBytes(2)
        val oracle = assertIs<Http3Frame.Unknown>(HandwrittenHttp3FrameCodec.decode(buffer(wire), DecodeContext.Empty))
        val generated = assertIs<Http3Frame.Unknown>(Http3FrameCodec.decode(buffer(wire), DecodeContext.Empty))
        assertEquals(type, oracle.type)
        assertEquals(type, generated.type)
    }

    @Test
    fun peekParity_dripFedOneByteAtATime() {
        // Verdict-shape note: the hand-written peek reports Complete(total) as
        // soon as the two header varints are readable — [Http3StreamReader]
        // then gates on `processor.available() >= peek.bytes` itself. The
        // generated peek folds that availability check in and reports
        // Complete only once the whole frame is buffered. The reader-visible
        // DECISION ("is a whole frame decodable now?") is what must agree at
        // every prefix, and the Complete value must agree once both complete.
        val images =
            listOf(
                handwrittenEncode(Http3Frame.Data(payload = buffer(payloadBytes(3)))),
                handwrittenEncode(Http3Frame.Data(payload = buffer(payloadBytes(200)))),
                handwrittenEncode(Http3Frame.GoAway(12345)),
                varint(0x1000) + varint(6) + payloadBytes(6), // unknown, 2-byte type
            )
        for (wire in images) {
            val pool = BufferPool()
            val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
            try {
                for (i in wire.indices) {
                    val one: PlatformBuffer = BufferFactory.Default.allocate(1)
                    one.writeByte(wire[i])
                    one.resetForRead()
                    stream.append(one)
                    val oracle = HandwrittenHttp3FrameCodec.peekFrameSize(stream, 0)
                    val generated = Http3FrameCodec.peekFrameSize(stream, 0)
                    val oracleReady = oracle is PeekResult.Complete && stream.available() >= oracle.bytes
                    val generatedReady = generated is PeekResult.Complete && stream.available() >= generated.bytes
                    assertEquals(oracleReady, generatedReady, "reader decision after ${i + 1}/${wire.size} bytes")
                    val fullyBuffered = i == wire.size - 1
                    assertEquals(fullyBuffered, generatedReady, "decision verdict after ${i + 1}/${wire.size} bytes")
                    if (fullyBuffered) {
                        assertEquals(PeekResult.Complete(wire.size), generated, "generated total")
                        assertEquals(PeekResult.Complete(wire.size), oracle, "oracle total")
                    }
                }
            } finally {
                stream.release()
                pool.clear()
            }
        }
    }

    @Test
    fun malformed_lengthPastBufferEnd_outcomeParity() {
        // DATA declaring 10 payload bytes with only 2 present. JVM/native
        // buffers throw on the over-read; the JS buffer clamps and both
        // codecs decode a truncated payload (pre-existing platform
        // leniency). The contract here is PARITY: the generated codec does
        // whatever the oracle did on this platform.
        val wire = byteArrayOf(0x00) + varint(10) + payloadBytes(2)
        val oracle = runCatching { HandwrittenHttp3FrameCodec.decode(buffer(wire), DecodeContext.Empty) }
        val generated = runCatching { Http3FrameCodec.decode(buffer(wire), DecodeContext.Empty) }
        assertEquals(oracle.isFailure, generated.isFailure, "outcome parity for truncated DATA")
        if (oracle.isSuccess) {
            assertFramesEqual(oracle.getOrThrow(), generated.getOrThrow(), "truncated DATA (lenient platform)")
        }
    }

    @Test
    fun malformed_settingsEntryStraddlingFrameEnd_bothThrow() {
        // SETTINGS length 2: identifier 0x01 then a 2-byte value varint whose
        // second byte lies past the frame end.
        val wire = byteArrayOf(0x04) + varint(2) + byteArrayOf(0x01, 0x40)
        assertFails { HandwrittenHttp3FrameCodec.decode(buffer(wire), DecodeContext.Empty) }
        assertFails { Http3FrameCodec.decode(buffer(wire), DecodeContext.Empty) }
    }

    @Test
    fun documentedDivergence_singleVarintFrameWithTrailingBytes() {
        // GOAWAY length 3: a 1-byte id varint + 2 stray bytes inside the frame.
        // Oracle (hand-written): lenient — skips to the frame end (pre-existing
        // behavior). Generated: strict bound check throws, the RFC 9114 §7.1
        // H3_FRAME_ERROR behavior. The swap to the generated codec is a
        // deliberate strictness upgrade, pinned here.
        val wire = byteArrayOf(0x07) + varint(3) + varint(5) + byteArrayOf(0x00, 0x00)
        val lenient = assertIs<Http3Frame.GoAway>(HandwrittenHttp3FrameCodec.decode(buffer(wire), DecodeContext.Empty))
        assertEquals(5, lenient.id)
        assertFails { Http3FrameCodec.decode(buffer(wire), DecodeContext.Empty) }
    }

    @Test
    fun generatedEncodeIsNonDestructive() {
        val payload = buffer(payloadBytes(8))
        val frame = Http3Frame.Data(payload = payload)
        val first = generatedEncode(frame)
        assertEquals(8, payload.remaining(), "payload position restored after generated encode")
        assertContentEquals(first, generatedEncode(frame), "second generated encode identical")
    }
}
