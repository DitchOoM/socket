package com.ditchoom.socket.http3

// Shared QPACK (RFC 9204) helpers used across the field-section codec, the dynamic table, and the
// stateful encoder/decoder.

/** Per-entry overhead the dynamic-table size accounting adds to name+value lengths (RFC 9204 §3.2.1). */
internal const val QPACK_ENTRY_OVERHEAD: Long = 32

/**
 * UTF-8 byte length of [string] without allocating a `ByteArray` — the "length" QPACK uses for
 * dynamic-table size accounting (RFC 9204 §3.2.1) and string-literal framing.
 */
internal fun qpackUtf8ByteLength(string: CharSequence): Int {
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

/** Size an entry contributes to the dynamic table: name + value octets + 32 (RFC 9204 §3.2.1). */
internal fun qpackEntrySize(
    name: String,
    value: String,
): Long = qpackUtf8ByteLength(name).toLong() + qpackUtf8ByteLength(value).toLong() + QPACK_ENTRY_OVERHEAD
