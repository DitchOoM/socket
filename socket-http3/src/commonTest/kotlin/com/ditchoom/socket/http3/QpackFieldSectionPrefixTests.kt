package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** RFC 9204 §4.5.1 field-section prefix: Required Insert Count wrapping + signed Base round-trips. */
class QpackFieldSectionPrefixTests {
    private fun roundTrip(
        requiredInsertCount: Long,
        base: Long,
        maxEntries: Long,
        totalInserts: Long,
    ): QpackPrefix {
        val buf = BufferFactory.Default.allocate(16)
        QpackFieldSectionPrefix.encode(buf, requiredInsertCount, base, maxEntries)
        buf.resetForRead()
        return QpackFieldSectionPrefix.decode(buf, maxEntries, totalInserts)
    }

    @Test
    fun zeroRequiredInsertCountAndBase() {
        // The static-only prefix: RIC 0, Base 0 — unaffected by MaxEntries / totalInserts.
        assertEquals(QpackPrefix(0, 0), roundTrip(0, 0, maxEntries = 31, totalInserts = 0))
    }

    @Test
    fun baseEqualToRequiredInsertCount() {
        assertEquals(QpackPrefix(3, 3), roundTrip(3, 3, maxEntries = 31, totalInserts = 3))
    }

    @Test
    fun baseGreaterThanRic_sign0() {
        // Base > RIC: S=0, DeltaBase = Base - RIC.
        assertEquals(QpackPrefix(3, 7), roundTrip(3, 7, maxEntries = 31, totalInserts = 7))
    }

    @Test
    fun baseLessThanRic_sign1_postBase() {
        // Base < RIC: S=1, DeltaBase = RIC - Base - 1 (post-Base references follow).
        assertEquals(QpackPrefix(5, 2), roundTrip(5, 2, maxEntries = 31, totalInserts = 5))
    }

    @Test
    fun requiredInsertCountWrapsAroundMaxEntries() {
        // maxEntries=10 → FullRange=20. RIC=25 encodes to 25%20+1=6; with totalInserts=25 the
        // §4.5.1.1 reconstruction must recover 25, not 5.
        assertEquals(QpackPrefix(25, 25), roundTrip(25, 25, maxEntries = 10, totalInserts = 25))
    }

    @Test
    fun decodeRecoversRicWhenDecoderHasInsertedMoreSince() {
        // Encoder used RIC=8; by decode time the decoder has totalInserts=12 (more arrived). Still 8.
        assertEquals(8L, roundTrip(8, 8, maxEntries = 31, totalInserts = 12).requiredInsertCount)
    }

    @Test
    fun decodeRejectsEncodedInsertCountAboveFullRange() {
        // maxEntries=10 → FullRange=20; a wire EncInsertCount of 21 is invalid (RFC 9204 §4.5.1.1).
        val buf = BufferFactory.Default.allocate(4)
        QpackPrefixedInteger.encode(buf, 21, prefixBits = 8)
        QpackPrefixedInteger.encode(buf, 0, prefixBits = 7) // Base prefix
        buf.resetForRead()
        assertFailsWith<DecodeException> { QpackFieldSectionPrefix.decode(buf, maxEntries = 10, totalInserts = 0) }
    }

    @Test
    fun decodeRejectsDynamicReferenceWhenTableDisabled() {
        // maxEntries=0 (we advertised capacity 0) but the peer sent a non-zero RIC → protocol error.
        val buf = BufferFactory.Default.allocate(4)
        QpackPrefixedInteger.encode(buf, 1, prefixBits = 8)
        QpackPrefixedInteger.encode(buf, 0, prefixBits = 7)
        buf.resetForRead()
        assertFailsWith<DecodeException> { QpackFieldSectionPrefix.decode(buf, maxEntries = 0, totalInserts = 0) }
    }
}
