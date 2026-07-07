package com.ditchoom.socket

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [compactCompat] is the manual replacement for [java.nio.ByteBuffer.compact] used by
 * [JvmTlsHandler] on the BUFFER_UNDERFLOW path. Stock `compact()` on an off-heap direct buffer
 * throws `NullPointerException: src == null` on Android's libcore (it reaches a `System.arraycopy`
 * against the null heap-backing array), so the handler must not call it.
 *
 * These tests pin `compactCompat` to the exact postcondition of `ByteBuffer.compact()`:
 * the unread region `[position, limit)` moves to the front, `position` becomes the old
 * `remaining()`, and `limit` becomes `capacity`. A heap buffer is used as the oracle because its
 * `compact()` behaves identically on every JVM; a direct buffer is exercised to prove the manual
 * path (the one that crashes on Android) produces the same bytes.
 */
class CompactCompatTest {
    private fun bytesFrom(
        buffer: ByteBuffer,
        count: Int,
    ): List<Byte> {
        val dup = buffer.duplicate()
        dup.position(0)
        return (0 until count).map { dup.get(it) }
    }

    /** Fill [0, capacity) with 1..capacity, then read `consumed` bytes so [consumed, limit) is unread. */
    private fun seed(
        buffer: ByteBuffer,
        filled: Int,
        consumed: Int,
    ) {
        buffer.clear()
        for (i in 0 until filled) buffer.put((i + 1).toByte())
        buffer.flip() // position=0, limit=filled
        buffer.position(consumed)
    }

    @Test
    fun matchesStockCompactAcrossPositions() {
        val capacity = 16
        for (filled in 0..capacity) {
            for (consumed in 0..filled) {
                val oracle = ByteBuffer.allocate(capacity)
                val actual = ByteBuffer.allocateDirect(capacity)
                seed(oracle, filled, consumed)
                seed(actual, filled, consumed)

                val expectedRemaining = filled - consumed
                oracle.compact()
                actual.compactCompat()

                assertEquals(
                    expectedRemaining,
                    actual.position(),
                    "position after compact (filled=$filled consumed=$consumed)",
                )
                assertEquals(capacity, actual.limit(), "limit after compact")
                assertEquals(oracle.position(), actual.position(), "position parity with stock compact")
                assertEquals(oracle.limit(), actual.limit(), "limit parity with stock compact")
                assertEquals(
                    bytesFrom(oracle, expectedRemaining),
                    bytesFrom(actual, expectedRemaining),
                    "compacted bytes (filled=$filled consumed=$consumed)",
                )
            }
        }
    }

    @Test
    fun preservesRemainingBytesForPartialTlsRecord() {
        // Models the handshake case: a partial record sits at the tail, engine wants more bytes.
        val buffer = ByteBuffer.allocateDirect(32)
        seed(buffer, filled = 20, consumed = 12) // 8 unread bytes (values 13..20)
        buffer.compactCompat()
        assertEquals(8, buffer.position())
        assertEquals(32, buffer.limit())
        assertEquals((13..20).map { it.toByte() }, bytesFrom(buffer, 8))
    }
}
