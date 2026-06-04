package com.ditchoom.socket.http3

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.pool.BufferPool

/**
 * A QPACK string literal (RFC 9204 §4.5.1's "String Literal", reused for names and values across the
 * field-section and encoder-stream representations). The first byte holds the representation's flag
 * bits, an `H` bit at position [prefixBits] (`1 shl prefixBits`), and a [prefixBits]-wide integer
 * length; the bytes follow, Huffman-coded ([QpackHuffman]) when `H=1` else raw UTF-8.
 *
 * Encoding chooses Huffman only when it is strictly shorter, matching the length [size] reports so
 * `wireSize` callers stay exact. The differing prefix widths (7 for values, 3/5 for names) are all
 * expressed by [prefixBits]; [flags] carries any representation pattern bits *above* the `H` bit.
 */
internal object QpackStringLiteral {
    fun write(
        buffer: WriteBuffer,
        string: String,
        prefixBits: Int,
        flags: Int = 0,
    ) {
        val raw = qpackUtf8ByteLength(string)
        val huffman = QpackHuffman.huffmanByteLength(string)
        val huffmanBit = 1 shl prefixBits
        if (huffman < raw) {
            QpackPrefixedInteger.encode(buffer, huffman.toLong(), prefixBits, firstByteFlags = flags or huffmanBit)
            QpackHuffman.encode(buffer, string)
        } else {
            QpackPrefixedInteger.encode(buffer, raw.toLong(), prefixBits, firstByteFlags = flags)
            buffer.writeString(string, Charset.UTF8)
        }
    }

    /** Bytes [write] occupies for [string] at [prefixBits] (Huffman when strictly shorter, else raw). */
    fun size(
        string: String,
        prefixBits: Int,
    ): Int {
        val bytes = minOf(qpackUtf8ByteLength(string), QpackHuffman.huffmanByteLength(string))
        return QpackPrefixedInteger.encodedLength(bytes.toLong(), prefixBits) + bytes
    }

    /** Read a string literal whose first byte hasn't been consumed yet. */
    fun read(
        buffer: ReadBuffer,
        prefixBits: Int,
        fieldName: String,
        scratchPool: BufferPool?,
    ): String = readFromFirstByte(buffer, buffer.readByte().toInt() and 0xFF, prefixBits, fieldName, scratchPool)

    /** Read a string literal given its already-read [firstByte] (the caller inspected its flag bits). */
    fun readFromFirstByte(
        buffer: ReadBuffer,
        firstByte: Int,
        prefixBits: Int,
        fieldName: String,
        scratchPool: BufferPool?,
    ): String {
        val huffman = firstByte and (1 shl prefixBits) != 0
        val length = QpackPrefixedInteger.decodeFromFirstByte(buffer, firstByte, prefixBits).toInt()
        // A truncated/hostile literal can declare a length past the buffer; reject rather than over-read.
        if (length > buffer.remaining()) {
            throw DecodeException(
                fieldPath = fieldName,
                bufferPosition = buffer.position(),
                expected = "$length string byte(s) available",
                actual = "${buffer.remaining()} byte(s) remaining",
            )
        }
        return if (huffman) {
            QpackHuffman.decode(buffer, length, fieldName, scratchPool)
        } else {
            buffer.readString(length, Charset.UTF8)
        }
    }
}
