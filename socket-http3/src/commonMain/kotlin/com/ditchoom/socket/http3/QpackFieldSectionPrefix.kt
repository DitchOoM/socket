package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.DecodeException

/** The decoded field-section prefix (RFC 9204 §4.5.1): the Required Insert Count and the Base. */
data class QpackPrefix(
    val requiredInsertCount: Long,
    val base: Long,
)

/**
 * Encodes/decodes the QPACK encoded-field-section **prefix** (RFC 9204 §4.5.1): the Required Insert
 * Count and the Base that precede the field lines. Both halves use [QpackPrefixedInteger].
 *
 * Required Insert Count is wrapped on the wire (§4.5.1.1) so it fits a small integer regardless of
 * how many total insertions the dynamic table has seen; reconstruction needs `MaxEntries` (the
 * `maxCapacity/32` ceiling) and the decoder's current total insert count. Base is signed relative to
 * the Required Insert Count (§4.5.1.2) so post-Base references can be expressed.
 */
object QpackFieldSectionPrefix {
    private const val SIGN_BIT = 0x80 // Base prefix: S bit in the first byte (7-bit DeltaBase prefix)

    fun encode(
        buffer: WriteBuffer,
        requiredInsertCount: Long,
        base: Long,
        maxEntries: Long,
    ) {
        // Required Insert Count (RFC 9204 §4.5.1.1): 0 stays 0; otherwise wrap into [1, 2*MaxEntries].
        val encInsertCount = if (requiredInsertCount == 0L) 0L else (requiredInsertCount % (2 * maxEntries)) + 1
        QpackPrefixedInteger.encode(buffer, encInsertCount, prefixBits = 8)
        // Base (RFC 9204 §4.5.1.2): S=0 with DeltaBase = Base - RIC when Base ≥ RIC, else S=1 with
        // DeltaBase = RIC - Base - 1 (post-Base references).
        if (base >= requiredInsertCount) {
            QpackPrefixedInteger.encode(buffer, base - requiredInsertCount, prefixBits = 7)
        } else {
            QpackPrefixedInteger.encode(buffer, requiredInsertCount - base - 1, prefixBits = 7, firstByteFlags = SIGN_BIT)
        }
    }

    /**
     * Decode the prefix. [maxEntries] is `maxCapacity / 32` for the decoder's table and [totalInserts]
     * is the decoder's current [QpackDynamicTable.insertCount]. Throws [DecodeException] on a Required
     * Insert Count the §4.5.1.1 reconstruction rejects (out of range / ambiguous / references the
     * dynamic table when it's disabled).
     */
    fun decode(
        buffer: ReadBuffer,
        maxEntries: Long,
        totalInserts: Long,
    ): QpackPrefix {
        val encInsertCount = QpackPrefixedInteger.decode(buffer, prefixBits = 8)
        val requiredInsertCount = decodeRequiredInsertCount(buffer, encInsertCount, maxEntries, totalInserts)
        val baseFirst = buffer.readByte().toInt() and 0xFF
        val sign = baseFirst and SIGN_BIT != 0
        val deltaBase = QpackPrefixedInteger.decodeFromFirstByte(buffer, baseFirst, prefixBits = 7)
        val base = if (!sign) requiredInsertCount + deltaBase else requiredInsertCount - deltaBase - 1
        if (base < 0) {
            throw DecodeException(
                fieldPath = "QpackFieldSection.base",
                bufferPosition = buffer.position(),
                expected = "a non-negative Base",
                actual = base.toString(),
            )
        }
        return QpackPrefix(requiredInsertCount, base)
    }

    /** RFC 9204 §4.5.1.1 Required Insert Count reconstruction. */
    private fun decodeRequiredInsertCount(
        buffer: ReadBuffer,
        encInsertCount: Long,
        maxEntries: Long,
        totalInserts: Long,
    ): Long {
        if (encInsertCount == 0L) return 0L
        if (maxEntries == 0L) {
            throw DecodeException(
                fieldPath = "QpackFieldSection.requiredInsertCount",
                bufferPosition = buffer.position(),
                expected = "0 (dynamic table disabled: MaxEntries == 0)",
                actual = "encoded $encInsertCount",
            )
        }
        val fullRange = 2 * maxEntries
        if (encInsertCount > fullRange) {
            throw decodeError(buffer, "Required Insert Count ≤ 2*MaxEntries ($fullRange)", encInsertCount.toString())
        }
        val maxValue = totalInserts + maxEntries
        val maxWrapped = (maxValue / fullRange) * fullRange
        var requiredInsertCount = maxWrapped + encInsertCount - 1
        if (requiredInsertCount > maxValue) {
            if (requiredInsertCount <= fullRange) {
                throw decodeError(buffer, "Required Insert Count ≤ MaxValue ($maxValue)", requiredInsertCount.toString())
            }
            requiredInsertCount -= fullRange
        }
        if (requiredInsertCount == 0L) {
            throw decodeError(buffer, "a non-zero Required Insert Count after reconstruction", "0")
        }
        return requiredInsertCount
    }

    private fun decodeError(
        buffer: ReadBuffer,
        expected: String,
        actual: String,
    ) = DecodeException(
        fieldPath = "QpackFieldSection.requiredInsertCount",
        bufferPosition = buffer.position(),
        expected = expected,
        actual = actual,
    )
}
