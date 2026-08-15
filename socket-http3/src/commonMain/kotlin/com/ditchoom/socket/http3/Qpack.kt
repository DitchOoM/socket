package com.ditchoom.socket.http3

import com.ditchoom.buffer.MalformedTextException
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.Utf8
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.utf8Size

// Shared QPACK (RFC 9204) helpers used across the field-section codec, the dynamic table, and the
// stateful encoder/decoder.

/** Per-entry overhead the dynamic-table size accounting adds to name+value lengths (RFC 9204 §3.2.1). */
internal const val QPACK_ENTRY_OVERHEAD: Long = 32

/**
 * Size an entry contributes to the dynamic table: name + value octets + 32 (RFC 9204 §3.2.1).
 *
 * [utf8Size] is [Utf8.Lenient]'s `size`, which buffer guarantees equals the bytes
 * `writeText(text, Utf8.Lenient)` emits — so the octet count charged here is the same function that
 * produces the string literal's length prefix, on every platform.
 */
internal fun qpackEntrySize(
    name: String,
    value: String,
): Long = name.utf8Size().toLong() + value.utf8Size().toLong() + QPACK_ENTRY_OVERHEAD

/**
 * Decode [length] bytes at the cursor as a QPACK string, rejecting ill-formed UTF-8 as a typed
 * [DecodeException] attributed to [fieldPath].
 *
 * [Utf8.Strict] is what makes malformed wire input a *codec* error on every platform. `readString`
 * reports it through whatever the host charset decoder raises — a `MalformedInputException` on the
 * JVM, a raw JS `TypeError` (a `Throwable` that is not a Kotlin `Exception`) on JS/wasmJs, nothing at
 * all on the targets that substitute U+FFFD instead — so a hostile peer could either crash the codec
 * with an untyped platform error or slip replacement characters through, depending on where the code
 * happened to be running. Strict raises one common [MalformedTextException] carrying the offset, and
 * rejects atomically: the cursor has not moved when this throws.
 */
internal fun ReadBuffer.readQpackText(
    length: Int,
    fieldPath: String,
): String =
    try {
        readText(length, Utf8.Strict)
    } catch (e: MalformedTextException) {
        val detail =
            when (e) {
                is MalformedTextException.IllFormedBytes -> "ill-formed UTF-8 at byte offset ${e.byteOffset}"
                is MalformedTextException.UnpairedSurrogate -> "unpaired surrogate at UTF-16 index ${e.charIndex}"
            }
        throw DecodeException(
            fieldPath = fieldPath,
            bufferPosition = position(),
            expected = "well-formed UTF-8",
            actual = detail,
            cause = e,
        )
    }
