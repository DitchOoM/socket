package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.StreamProcessor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 0 of the H3/QPACK conformance plan: a **deterministic, error-paths-first** corpus of crafted
 * malformed / edge inputs, each asserting the EXACT RFC-mandated outcome — a specific typed
 * [Http3ErrorCode] (errors stay typed, never strings) or that a reserved / GREASE construct is
 * **ignored**. Every case is a fixed byte vector, so this is 100% reproducible and runs on every
 * platform (jvm / js / linuxX64 / linuxArm64 / apple).
 *
 * This file holds the **codec-level** cases (the hand-rolled risk surface: [Http3FrameCodec],
 * [Http3StreamReader], [QpackDecoder], [QpackInstructionReader], [validatePeerSettings]). The
 * **connection-level** counterparts (a peer control stream that violates §7.2.4 / §7.1 over a
 * `FakeQuicScope`) live in `Http3ConnectionTests`, which already owns that harness.
 *
 * RFC map: HTTP/3 error codes RFC 9114 §8.1; SETTINGS rules §7.2.4.1; frame rules §7.1 / §9 / §11.2.1;
 * QPACK decode failures RFC 9204 §2.2; QPACK stream rules §4.2.
 */
class Http3ConformanceCorpusTests {
    private fun pool() =
        BufferPool(
            threadingMode = ThreadingMode.SingleThreaded,
            maxPoolSize = 8,
            defaultBufferSize = 256,
            factory = BufferFactory.Default,
        )

    private fun bufferOf(vararg bytes: Int): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return buf
    }

    private fun bytes(vararg b: Int): List<Int> = b.toList()

    /** A [ByteStream] that replays a fixed script of [ReadResult]s, then [ReadResult.End]. */
    private class ScriptedByteStream(
        results: List<ReadResult>,
    ) : ByteStream {
        private val queue = ArrayDeque(results)
        override val isOpen: Boolean get() = queue.isNotEmpty()
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds)

        override suspend fun read(deadline: Duration): ReadResult = if (queue.isEmpty()) ReadResult.End else queue.removeFirst()

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten = throw UnsupportedOperationException("read-only test stream")

        override suspend fun close() = Unit
    }

    private fun dataChunk(bytes: List<Int>): ReadResult {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return ReadResult.Data(buf)
    }

    private fun frameReaderOf(vararg streamBytes: Int): Http3StreamReader =
        Http3StreamReader(
            ScriptedByteStream(listOf(dataChunk(streamBytes.toList()), ReadResult.End)),
            StreamProcessor.create(pool(), ByteOrder.BIG_ENDIAN),
        )

    // =====================================================================================
    // A. SETTINGS validation (RFC 9114 §7.2.4.1) — H3_SETTINGS_ERROR, GREASE/unknown ignored.
    // =====================================================================================

    @Test
    fun settings_duplicateIdentifier_isSettingsError() {
        // "The same setting identifier MUST NOT occur more than once in the SETTINGS frame."
        val entries =
            listOf(
                Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 4096L),
                Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 0L),
            )
        val e = assertFailsWith<Http3StreamException> { validatePeerSettings(entries) }
        assertEquals(Http3ErrorCode.SETTINGS_ERROR, e.errorCode)
    }

    @Test
    fun settings_reservedHttp2Identifiers_areSettingsError() {
        // HTTP/2 setting ids reserved in HTTP/3 (§11.2.2): their receipt MUST be H3_SETTINGS_ERROR.
        for (reserved in Http3SettingId.RESERVED_HTTP2_IDS) {
            val e =
                assertFailsWith<Http3StreamException>("setting id 0x${reserved.toString(16)} must be rejected") {
                    validatePeerSettings(listOf(Http3Setting(reserved, 1L)))
                }
            assertEquals(Http3ErrorCode.SETTINGS_ERROR, e.errorCode, "reserved id 0x${reserved.toString(16)}")
        }
    }

    @Test
    fun settings_greaseAndUnknownIdentifiers_areIgnored() {
        // GREASE setting ids (0x1f*N+0x21) and other unknown ids are legal and simply not surfaced.
        val grease = 0x1fL * 7 + 0x21 // a GREASE setting identifier
        val settings =
            validatePeerSettings(
                listOf(
                    Http3Setting(grease, 0xdeadL),
                    Http3Setting(0x4d4dL, 1L), // an unknown (non-reserved) extension id
                    Http3Setting(Http3SettingId.MAX_FIELD_SECTION_SIZE, 16384L),
                ),
            )
        assertEquals(16384L, settings.maxFieldSectionSize, "known settings still parse; unknown ids ignored")
    }

    // =====================================================================================
    // B. Frame layer (RFC 9114 §7.1 / §9 / §11.2.1).
    // =====================================================================================

    @Test
    fun frame_trailingBytesPastFields_isFrameError() =
        runTest {
            // GOAWAY (type 0x07) Length=2 but its body is a single varint id (0x00); the extra byte
            // past the identified field is a malformed frame ⇒ H3_FRAME_ERROR (strict decode §7.1).
            val r = frameReaderOf(0x07, 0x02, 0x00, 0x00)
            val e = assertFailsWith<Http3StreamException> { r.nextFrame() }
            assertEquals(Http3ErrorCode.FRAME_ERROR, e.errorCode)
        }

    @Test
    fun frame_lengthAboveIntMax_isFrameError() {
        // A frame Length above Int.MAX can't bound a single buffer: the codec rejects it
        // (Http3LengthCodec) with a DecodeException, which Http3StreamReader types as H3_FRAME_ERROR.
        // 8-byte varint encoding of 0x80000000 (2^31, just over Int.MAX).
        val buf = bufferOf(0x00, 0xC0, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00)
        assertFailsWith<DecodeException> { Http3FrameCodec.decode(buf, DecodeContext.Empty) }
    }

    @Test
    fun frame_greaseType_decodesToUnknown_andIsIgnored() =
        runTest {
            // GREASE frame type 0x21 (= 0x1f*0+0x21) with a 1-byte payload: decodes to Unknown and a
            // consumer ignores it (RFC 9114 §9) — rejectIfReservedHttp2Frame() is a no-op for it.
            assertTrue(Http3FrameType.isReserved(0x21), "0x21 is a GREASE frame type")
            val frame = frameReaderOf(0x21, 0x01, 0xAA).nextFrame()
            assertIs<Http3Frame.Unknown>(frame)
            assertEquals(0x21L, frame.type)
            frame.rejectIfReservedHttp2Frame() // must NOT throw
        }

    @Test
    fun frame_reservedHttp2Types_areFrameUnexpected() {
        // HTTP/2 frame types with no HTTP/3 meaning (PRIORITY/PING/WINDOW_UPDATE/CONTINUATION) MUST be
        // H3_FRAME_UNEXPECTED on receipt (§7.1) — distinct from GREASE, which is ignored. The frame
        // codec still decodes them to Unknown (the type is opaque there); the consumer rejects them.
        for (reserved in listOf(0x02L, 0x06L, 0x08L, 0x09L)) {
            assertTrue(Http3FrameType.isReservedHttp2(reserved))
            val e =
                assertFailsWith<Http3StreamException>("reserved H2 frame 0x${reserved.toString(16)} must be rejected") {
                    Http3Frame.Unknown(reserved, ReadBuffer.EMPTY_BUFFER).rejectIfReservedHttp2Frame()
                }
            assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, e.errorCode, "reserved H2 frame 0x${reserved.toString(16)}")
        }
    }

    // =====================================================================================
    // C. QPACK field-section decode (RFC 9204 §2.2) — every failure is QPACK_DECOMPRESSION_FAILED.
    // =====================================================================================

    /** One crafted encoded field section and a human label. All use prefix RIC=0,Base=0 (0x00 0x00). */
    private data class QpackSection(
        val label: String,
        val bytes: List<Int>,
    )

    private val malformedSections =
        listOf(
            // Indexed Field Line, dynamic (T=0) relative index 0, but the dynamic table is empty.
            QpackSection("dynamic index into empty table", bytes(0x00, 0x00, 0x80)),
            // Indexed Field Line with Post-Base Index 0, dynamic table empty.
            QpackSection("post-base index into empty table", bytes(0x00, 0x00, 0x10)),
            // Literal w/ Name Ref (static name 0) + value literal whose declared length (10) exceeds bytes.
            QpackSection("value literal length past buffer", bytes(0x00, 0x00, 0x50, 0x0A, 0x41, 0x42)),
            // Literal w/ Name Ref (static name 0) + Huffman value (H=1,len=1) with an invalid code (0x1e).
            QpackSection("invalid Huffman in value literal", bytes(0x00, 0x00, 0x50, 0x81, 0x1e)),
        )

    @Test
    fun qpack_malformedFieldSections_areDecompressionFailed() =
        runTest {
            for (case in malformedSections) {
                val decoder = QpackDecoder(maxCapacity = 4096) { /* no decoder-stream output for RIC=0 */ }
                val e =
                    assertFailsWith<Http3StreamException>("expected decode failure for: ${case.label}") {
                        decoder.decodeSection(bufferOf(*case.bytes.toIntArray()), streamId = 0, scratchPool = null)
                    }
                assertEquals(Http3ErrorCode.QPACK_DECOMPRESSION_FAILED, e.errorCode, case.label)
            }
        }

    @Test
    fun qpack_validStaticSection_decodes() =
        runTest {
            // Positive control: RIC=0,Base=0 then an Indexed Field Line, static index 17 = (:method, GET).
            val decoder = QpackDecoder(maxCapacity = 4096) {}
            val fields = decoder.decodeSection(bufferOf(0x00, 0x00, 0xD1), streamId = 0, scratchPool = null)
            assertEquals(listOf(QpackHeaderField(":method", "GET")), fields)
        }

    // =====================================================================================
    // D. QPACK instruction streams (RFC 9204 §4.2) — critical streams; errors are typed per direction.
    // =====================================================================================

    @Test
    fun qpack_encoderStreamReset_isClosedCriticalStream() =
        runTest {
            // A peer reset of a QPACK encoder stream (a critical stream) is fatal (§4.2).
            val reader =
                QpackInstructionReader.encoder(
                    ScriptedByteStream(listOf(dataChunk(listOf(0x3F, 0xFF)), ReadResult.Reset)),
                    pool(),
                )
            val e = assertFailsWith<Http3StreamException> { while (reader.next() != null) Unit }
            assertEquals(Http3ErrorCode.CLOSED_CRITICAL_STREAM, e.errorCode)
        }

    @Test
    fun qpack_encoderStreamTruncatedInstruction_isEncoderStreamError() =
        runTest {
            // An encoder-stream Insert With Literal Name (opcode 0x60|H|len) declaring a 5-byte name but
            // ending mid-name at FIN: the instruction never completes ⇒ QPACK_ENCODER_STREAM_ERROR.
            val reader =
                QpackInstructionReader.encoder(
                    ScriptedByteStream(listOf(dataChunk(listOf(0x45, 0x41, 0x42)), ReadResult.End)),
                    pool(),
                )
            val e = assertFailsWith<Http3StreamException> { while (reader.next() != null) Unit }
            assertEquals(Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR, e.errorCode)
        }

    @Test
    fun qpack_decoderStreamTruncatedInstruction_isDecoderStreamError() =
        runTest {
            // A decoder-stream Insert Count Increment (opcode 0x00, prefix 6) with an over-long varint
            // continuation that never terminates before FIN ⇒ QPACK_DECODER_STREAM_ERROR.
            val reader =
                QpackInstructionReader.decoder(
                    ScriptedByteStream(listOf(dataChunk(listOf(0x3F, 0xFF, 0xFF)), ReadResult.End)),
                    pool(),
                )
            val e = assertFailsWith<Http3StreamException> { while (reader.next() != null) Unit }
            assertEquals(Http3ErrorCode.QPACK_DECODER_STREAM_ERROR, e.errorCode)
        }

    // =====================================================================================
    // E. Regression corpus — minimized crashers the Phase-1 invariant fuzzer found (each once leaked an
    //    untyped buffer failure from the *peek* stage; now typed). Deterministic forever after.
    // =====================================================================================

    @Test
    fun frame_lengthAboveIntMax_viaReader_isFrameError() =
        runTest {
            // The reader path for an over-Int.MAX frame Length: peekFrameSize reads the 8-byte length
            // varint (0x80000000) and rejects it before decode. Must surface as H3_FRAME_ERROR, not a
            // raw DecodeException leaking out of the peek. (Fuzz seed 0x7730100001, streamReader#0.)
            val r = frameReaderOf(0x00, 0xC0, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00)
            val e = assertFailsWith<Http3StreamException> { r.nextFrame() }
            assertEquals(Http3ErrorCode.FRAME_ERROR, e.errorCode)
        }

    @Test
    fun frame_totalSizeIntOverflow_viaReader_isFrameError() =
        runTest {
            // DATA frame with Length 0x7ffffff8 (just under Int.MAX, so Http3LengthCodec accepts it) — but
            // type+length+body overflows the reader's Int byte count to negative. Must be H3_FRAME_ERROR,
            // not an untyped readBuffer(negative) fault. (Jazzer http3CodecFuzz finding, 1054 execs.)
            val r = frameReaderOf(0x00, 0xC0, 0x00, 0x00, 0x00, 0x7F, 0xFF, 0xFF, 0xF8)
            val e = assertFailsWith<Http3StreamException> { r.nextFrame() }
            assertEquals(Http3ErrorCode.FRAME_ERROR, e.errorCode)
        }

    @Test
    fun qpack_encoderInstructionLengthOverflow_isEncoderStreamError() =
        runTest {
            // Insert With Literal Name (0x40 | H=0 | name-len prefix 0x1f) followed by a continuation run
            // that overflows the name length past Int — the peekLength under-read once leaked a raw
            // BufferUnderflowException. Must be QPACK_ENCODER_STREAM_ERROR. (Fuzz seed 0x7730100003.)
            val reader =
                QpackInstructionReader.encoder(
                    ScriptedByteStream(listOf(dataChunk(listOf(0x5F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)), ReadResult.End)),
                    pool(),
                )
            val e = assertFailsWith<Http3StreamException> { while (reader.next() != null) Unit }
            assertEquals(Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR, e.errorCode)
        }
}
