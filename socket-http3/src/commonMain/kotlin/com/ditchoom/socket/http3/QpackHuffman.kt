package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.pool.BufferPool

/**
 * QPACK/HPACK Huffman codec (RFC 7541 §5.2 and Appendix B — the 257-symbol code,
 * reused unchanged by QPACK per RFC 9204 §4.1.2).
 *
 * The code is a canonical, complete prefix code over octet values 0..255 plus a
 * 30-bit all-ones EOS symbol (256) reserved for padding. Servers Huffman-encode
 * response header strings even with the dynamic table disabled, so the [decode]
 * path is mandatory; [encode] is provided for symmetry and testing.
 *
 * ## Padding / EOS (RFC 7541 §5.2)
 *
 * An encoded string is octet-aligned by padding the final byte with the most
 * significant bits of EOS (i.e. all ones). On decode:
 *  - padding strictly longer than 7 bits is a decode error;
 *  - padding that is not a prefix of EOS (not all ones) is a decode error;
 *  - a decoded EOS symbol anywhere in the body is a decode error.
 *
 * Because EOS is all ones and no other symbol has an all-ones code, valid padding
 * is exactly "1..7 trailing one-bits that do not complete a symbol".
 *
 * Strings are pure octet sequences here; callers interpret them (the field-section
 * codec treats names/values as UTF-8).
 */
object QpackHuffman {
    private const val EOS = 256
    private const val SYMBOL_COUNT = 257
    private const val MAX_PADDING_BITS = 7

    /** Shortest code length, used to bound the decoded-octet count for allocation. */
    private const val MIN_CODE_BITS = 5

    /**
     * Per-symbol Huffman code, right-aligned (LSB) integer (RFC 7541 Appendix B,
     * "code as hex" column). Index is the octet value; entry 256 is EOS.
     */
    private val CODES =
        intArrayOf(
            0x1ff8,
            0x7fffd8,
            0xfffffe2,
            0xfffffe3,
            0xfffffe4,
            0xfffffe5,
            0xfffffe6,
            0xfffffe7,
            0xfffffe8,
            0xffffea,
            0x3ffffffc,
            0xfffffe9,
            0xfffffea,
            0x3ffffffd,
            0xfffffeb,
            0xfffffec,
            0xfffffed,
            0xfffffee,
            0xfffffef,
            0xffffff0,
            0xffffff1,
            0xffffff2,
            0x3ffffffe,
            0xffffff3,
            0xffffff4,
            0xffffff5,
            0xffffff6,
            0xffffff7,
            0xffffff8,
            0xffffff9,
            0xffffffa,
            0xffffffb,
            0x14,
            0x3f8,
            0x3f9,
            0xffa,
            0x1ff9,
            0x15,
            0xf8,
            0x7fa,
            0x3fa,
            0x3fb,
            0xf9,
            0x7fb,
            0xfa,
            0x16,
            0x17,
            0x18,
            0x0,
            0x1,
            0x2,
            0x19,
            0x1a,
            0x1b,
            0x1c,
            0x1d,
            0x1e,
            0x1f,
            0x5c,
            0xfb,
            0x7ffc,
            0x20,
            0xffb,
            0x3fc,
            0x1ffa,
            0x21,
            0x5d,
            0x5e,
            0x5f,
            0x60,
            0x61,
            0x62,
            0x63,
            0x64,
            0x65,
            0x66,
            0x67,
            0x68,
            0x69,
            0x6a,
            0x6b,
            0x6c,
            0x6d,
            0x6e,
            0x6f,
            0x70,
            0x71,
            0x72,
            0xfc,
            0x73,
            0xfd,
            0x1ffb,
            0x7fff0,
            0x1ffc,
            0x3ffc,
            0x22,
            0x7ffd,
            0x3,
            0x23,
            0x4,
            0x24,
            0x5,
            0x25,
            0x26,
            0x27,
            0x6,
            0x74,
            0x75,
            0x28,
            0x29,
            0x2a,
            0x7,
            0x2b,
            0x76,
            0x2c,
            0x8,
            0x9,
            0x2d,
            0x77,
            0x78,
            0x79,
            0x7a,
            0x7b,
            0x7ffe,
            0x7fc,
            0x3ffd,
            0x1ffd,
            0xffffffc,
            0xfffe6,
            0x3fffd2,
            0xfffe7,
            0xfffe8,
            0x3fffd3,
            0x3fffd4,
            0x3fffd5,
            0x7fffd9,
            0x3fffd6,
            0x7fffda,
            0x7fffdb,
            0x7fffdc,
            0x7fffdd,
            0x7fffde,
            0xffffeb,
            0x7fffdf,
            0xffffec,
            0xffffed,
            0x3fffd7,
            0x7fffe0,
            0xffffee,
            0x7fffe1,
            0x7fffe2,
            0x7fffe3,
            0x7fffe4,
            0x1fffdc,
            0x3fffd8,
            0x7fffe5,
            0x3fffd9,
            0x7fffe6,
            0x7fffe7,
            0xffffef,
            0x3fffda,
            0x1fffdd,
            0xfffe9,
            0x3fffdb,
            0x3fffdc,
            0x7fffe8,
            0x7fffe9,
            0x1fffde,
            0x7fffea,
            0x3fffdd,
            0x3fffde,
            0xfffff0,
            0x1fffdf,
            0x3fffdf,
            0x7fffeb,
            0x7fffec,
            0x1fffe0,
            0x1fffe1,
            0x3fffe0,
            0x1fffe2,
            0x7fffed,
            0x3fffe1,
            0x7fffee,
            0x7fffef,
            0xfffea,
            0x3fffe2,
            0x3fffe3,
            0x3fffe4,
            0x7ffff0,
            0x3fffe5,
            0x3fffe6,
            0x7ffff1,
            0x3ffffe0,
            0x3ffffe1,
            0xfffeb,
            0x7fff1,
            0x3fffe7,
            0x7ffff2,
            0x3fffe8,
            0x1ffffec,
            0x3ffffe2,
            0x3ffffe3,
            0x3ffffe4,
            0x7ffffde,
            0x7ffffdf,
            0x3ffffe5,
            0xfffff1,
            0x1ffffed,
            0x7fff2,
            0x1fffe3,
            0x3ffffe6,
            0x7ffffe0,
            0x7ffffe1,
            0x3ffffe7,
            0x7ffffe2,
            0xfffff2,
            0x1fffe4,
            0x1fffe5,
            0x3ffffe8,
            0x3ffffe9,
            0xffffffd,
            0x7ffffe3,
            0x7ffffe4,
            0x7ffffe5,
            0xfffec,
            0xfffff3,
            0xfffed,
            0x1fffe6,
            0x3fffe9,
            0x1fffe7,
            0x1fffe8,
            0x7ffff3,
            0x3fffea,
            0x3fffeb,
            0x1ffffee,
            0x1ffffef,
            0xfffff4,
            0xfffff5,
            0x3ffffea,
            0x7ffff4,
            0x3ffffeb,
            0x7ffffe6,
            0x3ffffec,
            0x3ffffed,
            0x7ffffe7,
            0x7ffffe8,
            0x7ffffe9,
            0x7ffffea,
            0x7ffffeb,
            0xffffffe,
            0x7ffffec,
            0x7ffffed,
            0x7ffffee,
            0x7ffffef,
            0x7fffff0,
            0x3ffffee,
            0x3fffffff,
        )

    /** Bit length of each symbol's code (RFC 7541 Appendix B, "len" column). */
    private val CODE_LENGTHS =
        intArrayOf(
            13,
            23,
            28,
            28,
            28,
            28,
            28,
            28,
            28,
            24,
            30,
            28,
            28,
            30,
            28,
            28,
            28,
            28,
            28,
            28,
            28,
            28,
            30,
            28,
            28,
            28,
            28,
            28,
            28,
            28,
            28,
            28,
            6,
            10,
            10,
            12,
            13,
            6,
            8,
            11,
            10,
            10,
            8,
            11,
            8,
            6,
            6,
            6,
            5,
            5,
            5,
            6,
            6,
            6,
            6,
            6,
            6,
            6,
            7,
            8,
            15,
            6,
            12,
            10,
            13,
            6,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            7,
            8,
            7,
            8,
            13,
            19,
            13,
            14,
            6,
            15,
            5,
            6,
            5,
            6,
            5,
            6,
            6,
            6,
            5,
            7,
            7,
            6,
            6,
            6,
            5,
            6,
            7,
            6,
            5,
            5,
            6,
            7,
            7,
            7,
            7,
            7,
            15,
            11,
            14,
            13,
            28,
            20,
            22,
            20,
            20,
            22,
            22,
            22,
            23,
            22,
            23,
            23,
            23,
            23,
            23,
            24,
            23,
            24,
            24,
            22,
            23,
            24,
            23,
            23,
            23,
            23,
            21,
            22,
            23,
            22,
            23,
            23,
            24,
            22,
            21,
            20,
            22,
            22,
            23,
            23,
            21,
            23,
            22,
            22,
            24,
            21,
            22,
            23,
            23,
            21,
            21,
            22,
            21,
            23,
            22,
            23,
            23,
            20,
            22,
            22,
            22,
            23,
            22,
            22,
            23,
            26,
            26,
            20,
            19,
            22,
            23,
            22,
            25,
            26,
            26,
            26,
            27,
            27,
            26,
            24,
            25,
            19,
            21,
            26,
            27,
            27,
            26,
            27,
            24,
            21,
            21,
            26,
            26,
            28,
            27,
            27,
            27,
            20,
            24,
            20,
            21,
            22,
            21,
            21,
            23,
            22,
            22,
            25,
            25,
            24,
            24,
            26,
            23,
            26,
            27,
            26,
            26,
            27,
            27,
            27,
            27,
            27,
            28,
            27,
            27,
            27,
            27,
            27,
            26,
            30,
        )

    // Decode trie over the complete prefix code, stored as two flat child arrays.
    // Node 0 is the root. A child slot holds:
    //   > 0  : index of an internal child node
    //   < 0  : a leaf, with symbol = -slot - 1
    //   == 0 : unset (never reached while decoding a complete prefix code)
    // A complete binary tree over 257 leaves has 256 internal nodes (513 total).
    private val child0 = IntArray(2 * SYMBOL_COUNT)
    private val child1 = IntArray(2 * SYMBOL_COUNT)

    init {
        var next = 1 // node 0 is the root; internal nodes are allocated from 1 up.
        for (sym in 0 until SYMBOL_COUNT) {
            val code = CODES[sym]
            val len = CODE_LENGTHS[sym]
            var node = 0
            for (i in len - 1 downTo 0) {
                val bit = (code ushr i) and 1
                val children = if (bit == 1) child1 else child0
                if (i == 0) {
                    children[node] = -sym - 1 // leaf
                } else {
                    var child = children[node]
                    if (child == 0) {
                        child = next++
                        children[node] = child
                    }
                    node = child
                }
            }
        }
    }

    /**
     * Decodes [byteLength] Huffman-coded bytes from [buffer] into the octet string
     * they represent, validating padding and rejecting EOS per RFC 7541 §5.2.
     *
     * @param fieldPath path reported on a [DecodeException]; the caller supplies its
     * own context (e.g. `"QpackFieldSection.value"`).
     * @param scratchPool optional pool the transient decode buffer is acquired from
     * and released back to; when null a one-shot buffer is allocated instead. The
     * scratch buffer never escapes (its octets are copied into the returned String),
     * so it is always released before returning — including on a decode error.
     */
    fun decode(
        buffer: ReadBuffer,
        byteLength: Int,
        fieldPath: String,
        scratchPool: BufferPool? = null,
    ): String {
        if (byteLength == 0) return ""
        // Shortest code is 5 bits, so at most floor(bits / 5) octets are produced.
        val capacity = byteLength * 8 / MIN_CODE_BITS + 1
        val out = scratchPool?.acquire(capacity) ?: BufferFactory.Default.allocate(capacity)
        try {
            var outLength = 0
            var node = 0
            var depth = 0 // bits walked since the last completed symbol (== root)
            var acc = 0 // those same bits as an integer, for the all-ones padding check
            var remaining = byteLength
            while (remaining-- > 0) {
                val octet = buffer.readByte().toInt() and 0xFF
                var bitIndex = 7
                while (bitIndex >= 0) {
                    val bit = (octet ushr bitIndex) and 1
                    bitIndex--
                    node = if (bit == 1) child1[node] else child0[node]
                    depth++
                    acc = (acc shl 1) or bit
                    if (node < 0) {
                        val symbol = -node - 1
                        if (symbol == EOS) {
                            throw DecodeException(
                                fieldPath = fieldPath,
                                bufferPosition = buffer.position(),
                                expected = "a Huffman string without the EOS symbol",
                                actual = "EOS symbol encountered in the string body",
                            )
                        }
                        out.writeByte(symbol.toByte())
                        outLength++
                        node = 0
                        depth = 0
                        acc = 0
                    }
                }
            }
            if (depth > 0) {
                if (depth > MAX_PADDING_BITS) {
                    throw DecodeException(
                        fieldPath = fieldPath,
                        bufferPosition = buffer.position(),
                        expected = "at most $MAX_PADDING_BITS bits of EOS padding",
                        actual = "$depth trailing bits that do not complete a symbol",
                    )
                }
                if (acc != (1 shl depth) - 1) {
                    throw DecodeException(
                        fieldPath = fieldPath,
                        bufferPosition = buffer.position(),
                        expected = "padding equal to the most significant bits of EOS (all ones)",
                        actual = "$depth trailing padding bits that are not all ones",
                    )
                }
            }
            out.resetForRead()
            return out.readString(outLength, Charset.UTF8)
        } finally {
            scratchPool?.release(out)
        }
    }

    /**
     * Huffman-encodes the UTF-8 octets of [value] into [buffer], padding the final
     * byte with the most significant bits of EOS (RFC 7541 §5.2). The field-section
     * codec calls this when [huffmanByteLength] is shorter than the raw UTF-8 length.
     */
    fun encode(
        buffer: WriteBuffer,
        value: CharSequence,
    ) {
        var acc = 0L
        var bits = 0
        forEachUtf8Octet(value) { octet ->
            acc = (acc shl CODE_LENGTHS[octet]) or CODES[octet].toLong()
            bits += CODE_LENGTHS[octet]
            while (bits >= 8) {
                bits -= 8
                buffer.writeByte(((acc ushr bits) and 0xFF).toByte())
            }
        }
        if (bits > 0) {
            val pad = 8 - bits
            acc = (acc shl pad) or ((1L shl pad) - 1) // EOS prefix == all ones
            buffer.writeByte((acc and 0xFF).toByte())
        }
    }

    /**
     * Number of bytes [encode] would emit for [value] — the Huffman-coded length,
     * rounded up to a byte. Lets the field-section codec choose Huffman only when it
     * is strictly shorter than the raw UTF-8 length. Derived from the same octet walk
     * as [encode], so the two never disagree.
     */
    fun huffmanByteLength(value: CharSequence): Int {
        var bits = 0L
        forEachUtf8Octet(value) { bits += CODE_LENGTHS[it] }
        return ((bits + 7) / 8).toInt()
    }

    /**
     * Invokes [action] for each UTF-8 octet of [value] without allocating a buffer or
     * ByteArray. Surrogate pairs become a 4-byte sequence (matching the field codec's
     * `utf8ByteLength`); an unpaired surrogate falls through to the 3-byte branch, the
     * same byte count platform `writeString` accounts for.
     */
    private inline fun forEachUtf8Octet(
        value: CharSequence,
        action: (Int) -> Unit,
    ) {
        var i = 0
        while (i < value.length) {
            val c = value[i].code
            when {
                c < 0x80 -> action(c)
                c < 0x800 -> {
                    action(0xC0 or (c shr 6))
                    action(0x80 or (c and 0x3F))
                }
                c in 0xD800..0xDBFF && i + 1 < value.length && value[i + 1].code in 0xDC00..0xDFFF -> {
                    val codePoint = 0x10000 + ((c - 0xD800) shl 10) + (value[i + 1].code - 0xDC00)
                    action(0xF0 or (codePoint shr 18))
                    action(0x80 or ((codePoint shr 12) and 0x3F))
                    action(0x80 or ((codePoint shr 6) and 0x3F))
                    action(0x80 or (codePoint and 0x3F))
                    i++ // consume the low surrogate
                }
                else -> {
                    action(0xE0 or (c shr 12))
                    action(0x80 or ((c shr 6) and 0x3F))
                    action(0x80 or (c and 0x3F))
                }
            }
            i++
        }
    }
}
