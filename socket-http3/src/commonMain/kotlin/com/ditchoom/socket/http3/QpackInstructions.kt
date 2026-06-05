package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * An instruction an encoder sends to the peer's decoder on the QPACK **encoder stream** (RFC 9204
 * §4.3): it mutates the decoder's dynamic table. [QpackEncoderInstructionCodec] is the byte format.
 */
sealed interface QpackEncoderInstruction {
    /** Set Dynamic Table Capacity (§4.3.1): `001` + 5-bit capacity. */
    data class SetCapacity(
        val capacity: Long,
    ) : QpackEncoderInstruction

    /** Insert with Name Reference (§4.3.2): `1` + T (static) + 6-bit name index, then the value literal. */
    data class InsertWithNameRef(
        val nameIndex: Long,
        val isStatic: Boolean,
        val value: String,
    ) : QpackEncoderInstruction

    /** Insert with Literal Name (§4.3.3): `01` + H + 5-bit name length, then name + value literals. */
    data class InsertWithLiteralName(
        val name: String,
        val value: String,
    ) : QpackEncoderInstruction

    /** Duplicate (§4.3.4): `000` + 5-bit relative index of an existing entry. */
    data class Duplicate(
        val index: Long,
    ) : QpackEncoderInstruction
}

/**
 * An instruction a decoder sends back to the peer's encoder on the QPACK **decoder stream** (RFC 9204
 * §4.4): it tells the encoder which entries are safely referenceable / no longer needed.
 */
sealed interface QpackDecoderInstruction {
    /** Section Acknowledgment (§4.4.1): `1` + 7-bit stream id — a field section on that stream decoded. */
    data class SectionAck(
        val streamId: Long,
    ) : QpackDecoderInstruction

    /** Stream Cancellation (§4.4.2): `01` + 6-bit stream id — the stream was reset/abandoned. */
    data class StreamCancellation(
        val streamId: Long,
    ) : QpackDecoderInstruction

    /** Insert Count Increment (§4.4.3): `00` + 6-bit increment — entries inserted since the last ack. */
    data class InsertCountIncrement(
        val increment: Long,
    ) : QpackDecoderInstruction
}

/** Byte format for [QpackEncoderInstruction] (RFC 9204 §4.3). */
object QpackEncoderInstructionCodec {
    private const val INSERT_NAME_REF = 0x80 // 1.......
    private const val INSERT_NAME_REF_STATIC = 0x40 // 1 T (T=1 static)
    private const val INSERT_LITERAL_NAME = 0x40 // 01......
    private const val SET_CAPACITY = 0x20 // 001.....
    // Duplicate is 000..... (no flag bits set above the 5-bit prefix).

    fun encode(
        buffer: WriteBuffer,
        instruction: QpackEncoderInstruction,
    ) {
        when (instruction) {
            is QpackEncoderInstruction.SetCapacity ->
                QpackPrefixedInteger.encode(buffer, instruction.capacity, prefixBits = 5, firstByteFlags = SET_CAPACITY)
            is QpackEncoderInstruction.InsertWithNameRef -> {
                val flags = INSERT_NAME_REF or (if (instruction.isStatic) INSERT_NAME_REF_STATIC else 0)
                QpackPrefixedInteger.encode(buffer, instruction.nameIndex, prefixBits = 6, firstByteFlags = flags)
                QpackStringLiteral.write(buffer, instruction.value, prefixBits = 7)
            }
            is QpackEncoderInstruction.InsertWithLiteralName -> {
                QpackStringLiteral.write(buffer, instruction.name, prefixBits = 5, flags = INSERT_LITERAL_NAME)
                QpackStringLiteral.write(buffer, instruction.value, prefixBits = 7)
            }
            is QpackEncoderInstruction.Duplicate ->
                QpackPrefixedInteger.encode(buffer, instruction.index, prefixBits = 5)
        }
    }

    /** Total byte length of the instruction at [baseOffset], or [PeekResult.NeedsMoreData] (§4.3 framing). */
    fun peekLength(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult {
        if (stream.available() < baseOffset + 1) return PeekResult.NeedsMoreData
        val first = stream.peekByte(baseOffset).toInt() and 0xFF
        return when {
            first and INSERT_NAME_REF != 0 -> {
                val name = QpackPrefixedInteger.peek(stream, baseOffset, prefixBits = 6) ?: return PeekResult.NeedsMoreData
                val value =
                    QpackStringLiteral.peekLength(stream, baseOffset + name.byteLength, prefixBits = 7)
                        ?: return PeekResult.NeedsMoreData
                PeekResult.Complete(name.byteLength + value)
            }
            first and INSERT_LITERAL_NAME != 0 -> {
                val name = QpackStringLiteral.peekLength(stream, baseOffset, prefixBits = 5) ?: return PeekResult.NeedsMoreData
                val value = QpackStringLiteral.peekLength(stream, baseOffset + name, prefixBits = 7) ?: return PeekResult.NeedsMoreData
                PeekResult.Complete(name + value)
            }
            else -> {
                // Set Capacity (001) and Duplicate (000) are both a single 5-bit-prefix integer.
                val p = QpackPrefixedInteger.peek(stream, baseOffset, prefixBits = 5) ?: return PeekResult.NeedsMoreData
                PeekResult.Complete(p.byteLength)
            }
        }
    }

    fun decode(
        buffer: ReadBuffer,
        scratchPool: BufferPool?,
    ): QpackEncoderInstruction {
        val first = buffer.readByte().toInt() and 0xFF
        return when {
            first and INSERT_NAME_REF != 0 -> {
                val isStatic = first and INSERT_NAME_REF_STATIC != 0
                val nameIndex = QpackPrefixedInteger.decodeFromFirstByte(buffer, first, prefixBits = 6)
                QpackEncoderInstruction.InsertWithNameRef(
                    nameIndex,
                    isStatic,
                    QpackStringLiteral.read(buffer, prefixBits = 7, "QpackEncoderInstruction.value", scratchPool),
                )
            }
            first and INSERT_LITERAL_NAME != 0 -> {
                val name = QpackStringLiteral.readFromFirstByte(buffer, first, prefixBits = 5, "QpackEncoderInstruction.name", scratchPool)
                val value = QpackStringLiteral.read(buffer, prefixBits = 7, "QpackEncoderInstruction.value", scratchPool)
                QpackEncoderInstruction.InsertWithLiteralName(name, value)
            }
            first and SET_CAPACITY != 0 ->
                QpackEncoderInstruction.SetCapacity(QpackPrefixedInteger.decodeFromFirstByte(buffer, first, prefixBits = 5))
            else ->
                QpackEncoderInstruction.Duplicate(QpackPrefixedInteger.decodeFromFirstByte(buffer, first, prefixBits = 5))
        }
    }
}

/** Byte format for [QpackDecoderInstruction] (RFC 9204 §4.4). */
object QpackDecoderInstructionCodec {
    private const val SECTION_ACK = 0x80 // 1.......
    private const val STREAM_CANCELLATION = 0x40 // 01......
    // Insert Count Increment is 00...... (no flag bits set above the 6-bit prefix).

    fun encode(
        buffer: WriteBuffer,
        instruction: QpackDecoderInstruction,
    ) {
        when (instruction) {
            is QpackDecoderInstruction.SectionAck ->
                QpackPrefixedInteger.encode(buffer, instruction.streamId, prefixBits = 7, firstByteFlags = SECTION_ACK)
            is QpackDecoderInstruction.StreamCancellation ->
                QpackPrefixedInteger.encode(buffer, instruction.streamId, prefixBits = 6, firstByteFlags = STREAM_CANCELLATION)
            is QpackDecoderInstruction.InsertCountIncrement ->
                QpackPrefixedInteger.encode(buffer, instruction.increment, prefixBits = 6)
        }
    }

    /** Total byte length of the instruction at [baseOffset], or [PeekResult.NeedsMoreData] (§4.4 framing). */
    fun peekLength(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult {
        if (stream.available() < baseOffset + 1) return PeekResult.NeedsMoreData
        val first = stream.peekByte(baseOffset).toInt() and 0xFF
        val prefixBits = if (first and SECTION_ACK != 0) 7 else 6 // 1xxxxxxx vs 01xxxxxx / 00xxxxxx
        val p = QpackPrefixedInteger.peek(stream, baseOffset, prefixBits) ?: return PeekResult.NeedsMoreData
        return PeekResult.Complete(p.byteLength)
    }

    fun decode(buffer: ReadBuffer): QpackDecoderInstruction {
        val first = buffer.readByte().toInt() and 0xFF
        return when {
            first and SECTION_ACK != 0 ->
                QpackDecoderInstruction.SectionAck(QpackPrefixedInteger.decodeFromFirstByte(buffer, first, prefixBits = 7))
            first and STREAM_CANCELLATION != 0 ->
                QpackDecoderInstruction.StreamCancellation(QpackPrefixedInteger.decodeFromFirstByte(buffer, first, prefixBits = 6))
            else ->
                QpackDecoderInstruction.InsertCountIncrement(QpackPrefixedInteger.decodeFromFirstByte(buffer, first, prefixBits = 6))
        }
    }
}
