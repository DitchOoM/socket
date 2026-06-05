package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals

/** Round-trips for the QPACK encoder-stream (§4.3) and decoder-stream (§4.4) instruction codecs. */
class QpackInstructionsTests {
    private fun encoderRoundTrip(instruction: QpackEncoderInstruction): QpackEncoderInstruction {
        val buf = BufferFactory.Default.allocate(256)
        QpackEncoderInstructionCodec.encode(buf, instruction)
        buf.resetForRead()
        return QpackEncoderInstructionCodec.decode(buf, scratchPool = null)
    }

    private fun decoderRoundTrip(instruction: QpackDecoderInstruction): QpackDecoderInstruction {
        val buf = BufferFactory.Default.allocate(64)
        QpackDecoderInstructionCodec.encode(buf, instruction)
        buf.resetForRead()
        return QpackDecoderInstructionCodec.decode(buf)
    }

    @Test
    fun setCapacityRoundTrips() {
        assertEquals(QpackEncoderInstruction.SetCapacity(4096), encoderRoundTrip(QpackEncoderInstruction.SetCapacity(4096)))
        assertEquals(QpackEncoderInstruction.SetCapacity(0), encoderRoundTrip(QpackEncoderInstruction.SetCapacity(0)))
    }

    @Test
    fun insertWithNameRefRoundTrips() {
        // Static name ref (e.g. ":path" in the static table) + a Huffman-compressible value.
        val instr = QpackEncoderInstruction.InsertWithNameRef(nameIndex = 1, isStatic = true, value = "/index.html")
        assertEquals(instr, encoderRoundTrip(instr))
        val dynamic = QpackEncoderInstruction.InsertWithNameRef(nameIndex = 3, isStatic = false, value = "custom-value")
        assertEquals(dynamic, encoderRoundTrip(dynamic))
    }

    @Test
    fun insertWithLiteralNameRoundTrips() {
        val instr = QpackEncoderInstruction.InsertWithLiteralName(name = "x-custom-header", value = "some-value-123")
        assertEquals(instr, encoderRoundTrip(instr))
    }

    @Test
    fun duplicateRoundTrips() {
        assertEquals(QpackEncoderInstruction.Duplicate(0), encoderRoundTrip(QpackEncoderInstruction.Duplicate(0)))
        assertEquals(QpackEncoderInstruction.Duplicate(42), encoderRoundTrip(QpackEncoderInstruction.Duplicate(42)))
    }

    @Test
    fun decoderInstructionsRoundTrip() {
        assertEquals(QpackDecoderInstruction.SectionAck(7), decoderRoundTrip(QpackDecoderInstruction.SectionAck(7)))
        assertEquals(
            QpackDecoderInstruction.StreamCancellation(200),
            decoderRoundTrip(QpackDecoderInstruction.StreamCancellation(200)),
        )
        assertEquals(
            QpackDecoderInstruction.InsertCountIncrement(5),
            decoderRoundTrip(QpackDecoderInstruction.InsertCountIncrement(5)),
        )
    }

    @Test
    fun multipleEncoderInstructionsDecodeBackToBack() {
        // The encoder stream packs instructions with no outer delimiter; decode reads them in order.
        val buf = BufferFactory.Default.allocate(256)
        val sequence =
            listOf(
                QpackEncoderInstruction.SetCapacity(4096),
                QpackEncoderInstruction.InsertWithLiteralName("x-a", "1"),
                QpackEncoderInstruction.InsertWithNameRef(0, isStatic = true, value = "v"),
                QpackEncoderInstruction.Duplicate(0),
            )
        sequence.forEach { QpackEncoderInstructionCodec.encode(buf, it) }
        buf.resetForRead()
        val decoded = sequence.indices.map { QpackEncoderInstructionCodec.decode(buf, scratchPool = null) }
        assertEquals(sequence, decoded)
    }
}
