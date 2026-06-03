package com.ditchoom.socket.http3

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * QPACK codec for an encoded field section (RFC 9204 §4.5), restricted to the
 * static table. The dynamic table is disabled, so the encoder always emits a
 * Required Insert Count of 0 and produces only static references and literals;
 * the decoder rejects anything that would require the dynamic table.
 *
 * Representations (RFC 9204 §4.5.2/§4.5.4/§4.5.6):
 * ```
 *   Indexed Field Line (static):           1 1 <index:6>
 *   Literal w/ Name Reference (static):    0 1 N 1 <nameIndex:4>  <value>
 *   Literal w/ Literal Name:               0 0 1 N H <nameLen:3>  <name> <value>
 * ```
 * Integers use [QpackPrefixedInteger]; the static table is [QpackStaticTable].
 *
 * String literals are written without Huffman (H=0); both raw (H=0) and
 * Huffman-coded (H=1) strings are decoded ([QpackHuffman]). Post-base and
 * dynamic-table representations are rejected. Names/values are treated as UTF-8.
 *
 * A field section is delimited by its enclosing HEADERS frame, not self-framing,
 * so [decode] consumes representations until the buffer is exhausted and
 * [peekFrameSize] reports [PeekResult.NoFraming].
 */
object QpackFieldSectionCodec : Codec<List<QpackHeaderField>> {
    // First-byte flag bits (the low prefix bits stay 0 — see QpackPrefixedInteger).
    private const val INDEXED_STATIC = 0xC0 // 1 1 ......  (indexed, T=static)
    private const val LITERAL_NAME_REF_STATIC = 0x50 // 0 1 0 1 ....  (name-ref, N=0, T=static)
    private const val LITERAL_LITERAL_NAME = 0x20 // 0 0 1 0 0 ...  (literal name, N=0, H=0)
    private const val HUFFMAN_BIT = 0x80 // string literal H bit (7-bit length prefix)

    override fun encode(
        buffer: WriteBuffer,
        value: List<QpackHeaderField>,
        context: EncodeContext,
    ) {
        // Field section prefix: Required Insert Count = 0, Delta Base = 0 (S=0).
        QpackPrefixedInteger.encode(buffer, 0, prefixBits = 8)
        QpackPrefixedInteger.encode(buffer, 0, prefixBits = 7)
        for (field in value) {
            val exact = QpackStaticTable.findExact(field.name, field.value)
            if (exact != null) {
                QpackPrefixedInteger.encode(buffer, exact.toLong(), prefixBits = 6, firstByteFlags = INDEXED_STATIC)
                continue
            }
            val nameIndex = QpackStaticTable.findName(field.name)
            if (nameIndex != null) {
                QpackPrefixedInteger.encode(buffer, nameIndex.toLong(), prefixBits = 4, firstByteFlags = LITERAL_NAME_REF_STATIC)
            } else {
                QpackPrefixedInteger.encode(
                    buffer,
                    utf8ByteLength(field.name).toLong(),
                    prefixBits = 3,
                    firstByteFlags = LITERAL_LITERAL_NAME,
                )
                buffer.writeString(field.name, Charset.UTF8)
            }
            writeStringLiteral(buffer, field.value)
        }
    }

    private fun writeStringLiteral(
        buffer: WriteBuffer,
        string: String,
    ) {
        // H=0: the 7-bit length prefix occupies the first byte, high bit clear.
        QpackPrefixedInteger.encode(buffer, utf8ByteLength(string).toLong(), prefixBits = 7)
        buffer.writeString(string, Charset.UTF8)
    }

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): List<QpackHeaderField> {
        val requiredInsertCount = QpackPrefixedInteger.decode(buffer, prefixBits = 8)
        if (requiredInsertCount != 0L) {
            throw DecodeException(
                fieldPath = "QpackFieldSection.requiredInsertCount",
                bufferPosition = buffer.position(),
                expected = "0 (dynamic table disabled)",
                actual = requiredInsertCount.toString(),
            )
        }
        // Delta Base (S bit + 7-bit prefix) — read to advance; unused with RIC=0.
        val deltaBaseFirst = buffer.readByte().toInt() and 0xFF
        QpackPrefixedInteger.decodeFromFirstByte(buffer, deltaBaseFirst, prefixBits = 7)

        val fields = mutableListOf<QpackHeaderField>()
        while (buffer.hasRemaining()) {
            fields.add(decodeFieldLine(buffer))
        }
        return fields
    }

    private fun decodeFieldLine(buffer: ReadBuffer): QpackHeaderField {
        val first = buffer.readByte().toInt() and 0xFF
        return when {
            first and 0x80 != 0 -> {
                // Indexed Field Line: 1 T <index:6>; T (bit 6) = 1 → static table.
                requireStatic(buffer, first and 0x40 != 0, "indexed field line")
                val index = QpackPrefixedInteger.decodeFromFirstByte(buffer, first, prefixBits = 6).toInt()
                staticEntry(buffer, index)
            }
            first and 0x40 != 0 -> {
                // Literal w/ Name Reference: 0 1 N T <nameIndex:4>; T (bit 4) = 1 → static.
                requireStatic(buffer, first and 0x10 != 0, "literal field line with name reference")
                val nameIndex = QpackPrefixedInteger.decodeFromFirstByte(buffer, first, prefixBits = 4).toInt()
                QpackHeaderField(staticEntry(buffer, nameIndex).name, readStringLiteral(buffer))
            }
            first and 0x20 != 0 -> {
                // Literal w/ Literal Name: 0 0 1 N H <nameLen:3> <name> <value>.
                val huffman = first and 0x08 != 0
                val nameLength = QpackPrefixedInteger.decodeFromFirstByte(buffer, first, prefixBits = 3).toInt()
                val name = readString(buffer, nameLength, huffman, "name")
                QpackHeaderField(name, readStringLiteral(buffer))
            }
            else ->
                throw DecodeException(
                    fieldPath = "QpackFieldSection",
                    bufferPosition = buffer.position(),
                    expected = "a static indexed or literal representation",
                    actual = "post-base or dynamic-table representation (first byte 0x${first.toByte().toHexPad()})",
                )
        }
    }

    private fun readStringLiteral(buffer: ReadBuffer): String {
        val first = buffer.readByte().toInt() and 0xFF
        val huffman = first and HUFFMAN_BIT != 0
        val length = QpackPrefixedInteger.decodeFromFirstByte(buffer, first, prefixBits = 7).toInt()
        return readString(buffer, length, huffman, "value")
    }

    private fun readString(
        buffer: ReadBuffer,
        length: Int,
        huffman: Boolean,
        what: String,
    ): String {
        // A hostile/truncated representation can declare a length past the buffer;
        // reject it cleanly rather than over-reading (applies to both encodings —
        // [length] counts the encoded bytes, Huffman or raw).
        if (length > buffer.remaining()) {
            throw DecodeException(
                fieldPath = "QpackFieldSection.$what",
                bufferPosition = buffer.position(),
                expected = "$length string byte(s) within the field section",
                actual = "${buffer.remaining()} byte(s) remaining",
            )
        }
        return if (huffman) {
            QpackHuffman.decode(buffer, length, "QpackFieldSection.$what")
        } else {
            buffer.readString(length, Charset.UTF8)
        }
    }

    /** Static-table lookup that surfaces an out-of-range index as a [DecodeException]. */
    private fun staticEntry(
        buffer: ReadBuffer,
        index: Int,
    ): QpackHeaderField {
        if (index !in 0 until QpackStaticTable.size) {
            throw DecodeException(
                fieldPath = "QpackFieldSection",
                bufferPosition = buffer.position(),
                expected = "a static table index in 0..${QpackStaticTable.size - 1}",
                actual = index.toString(),
            )
        }
        return QpackStaticTable.entry(index)
    }

    private fun requireStatic(
        buffer: ReadBuffer,
        isStatic: Boolean,
        representation: String,
    ) {
        if (!isStatic) {
            throw DecodeException(
                fieldPath = "QpackFieldSection",
                bufferPosition = buffer.position(),
                expected = "a static-table reference",
                actual = "dynamic-table $representation — dynamic table is disabled",
            )
        }
    }

    override fun wireSize(
        value: List<QpackHeaderField>,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(PREFIX_SIZE + value.sumOf { fieldLineSize(it) })

    private fun fieldLineSize(field: QpackHeaderField): Int {
        QpackStaticTable.findExact(field.name, field.value)?.let {
            return QpackPrefixedInteger.encodedLength(it.toLong(), prefixBits = 6)
        }
        val nameIndex = QpackStaticTable.findName(field.name)
        return if (nameIndex != null) {
            QpackPrefixedInteger.encodedLength(nameIndex.toLong(), prefixBits = 4) + stringLiteralSize(field.value)
        } else {
            val nameBytes = utf8ByteLength(field.name)
            QpackPrefixedInteger.encodedLength(nameBytes.toLong(), prefixBits = 3) + nameBytes + stringLiteralSize(field.value)
        }
    }

    private fun stringLiteralSize(string: String): Int {
        val bytes = utf8ByteLength(string)
        return QpackPrefixedInteger.encodedLength(bytes.toLong(), prefixBits = 7) + bytes
    }

    /** A field section is delimited by its enclosing HEADERS frame, not self-framing. */
    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult = PeekResult.NoFraming

    /** UTF-8 byte length of [string] without allocating a ByteArray. */
    private fun utf8ByteLength(string: CharSequence): Int {
        var bytes = 0
        var i = 0
        while (i < string.length) {
            val code = string[i].code
            bytes +=
                when {
                    code < 0x80 -> 1
                    code < 0x800 -> 2
                    code in 0xD800..0xDBFF && i + 1 < string.length && string[i + 1].code in 0xDC00..0xDFFF -> {
                        i++ // consume the low surrogate; a full code point is 4 UTF-8 bytes
                        4
                    }
                    else -> 3
                }
            i++
        }
        return bytes
    }

    private const val PREFIX_SIZE = 2 // RIC (0x00) + Delta Base (0x00)

    private fun Byte.toHexPad(): String = (toInt() and 0xFF).toString(16).padStart(2, '0')
}
