package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuicStreamIdTests {
    // RFC 9000 §2.1: Stream ID bit layout
    // Bit 0: initiator (0 = client, 1 = server)
    // Bit 1: directionality (0 = bidi, 1 = uni)

    @Test
    fun clientInitiatedBidi_id0() {
        val id = QuicStreamId(0)
        assertTrue(id.isClientInitiated)
        assertFalse(id.isServerInitiated)
        assertTrue(id.isBidirectional)
        assertFalse(id.isUnidirectional)
    }

    @Test
    fun serverInitiatedBidi_id1() {
        val id = QuicStreamId(1)
        assertTrue(id.isServerInitiated)
        assertFalse(id.isClientInitiated)
        assertTrue(id.isBidirectional)
        assertFalse(id.isUnidirectional)
    }

    @Test
    fun clientInitiatedUni_id2() {
        val id = QuicStreamId(2)
        assertTrue(id.isClientInitiated)
        assertFalse(id.isServerInitiated)
        assertTrue(id.isUnidirectional)
        assertFalse(id.isBidirectional)
    }

    @Test
    fun serverInitiatedUni_id3() {
        val id = QuicStreamId(3)
        assertTrue(id.isServerInitiated)
        assertFalse(id.isClientInitiated)
        assertTrue(id.isUnidirectional)
        assertFalse(id.isBidirectional)
    }

    @Test
    fun clientBidiSequence_incrementsBy4() {
        // Client bidi streams: 0, 4, 8, 12, ...
        val ids = (0..3).map { QuicStreamId(it.toLong() * 4) }
        ids.forEach { id ->
            assertTrue(id.isClientInitiated)
            assertTrue(id.isBidirectional)
        }
    }

    @Test
    fun serverBidiSequence_incrementsBy4() {
        // Server bidi streams: 1, 5, 9, 13, ...
        val ids = (0..3).map { QuicStreamId(it.toLong() * 4 + 1) }
        ids.forEach { id ->
            assertTrue(id.isServerInitiated)
            assertTrue(id.isBidirectional)
        }
    }

    @Test
    fun clientUniSequence_incrementsBy4() {
        // Client uni streams: 2, 6, 10, 14, ...
        val ids = (0..3).map { QuicStreamId(it.toLong() * 4 + 2) }
        ids.forEach { id ->
            assertTrue(id.isClientInitiated)
            assertTrue(id.isUnidirectional)
        }
    }

    @Test
    fun serverUniSequence_incrementsBy4() {
        // Server uni streams: 3, 7, 11, 15, ...
        val ids = (0..3).map { QuicStreamId(it.toLong() * 4 + 3) }
        ids.forEach { id ->
            assertTrue(id.isServerInitiated)
            assertTrue(id.isUnidirectional)
        }
    }

    @Test
    fun negativeId_throws() {
        assertFailsWith<IllegalArgumentException> {
            QuicStreamId(-1)
        }
    }

    @Test
    fun comparable_ordersById() {
        val sorted = listOf(QuicStreamId(4), QuicStreamId(0), QuicStreamId(2)).sorted()
        assertEquals(listOf(QuicStreamId(0), QuicStreamId(2), QuicStreamId(4)), sorted)
    }

    @Test
    fun equality_basedOnId() {
        assertEquals(QuicStreamId(42), QuicStreamId(42))
    }

    @Test
    fun toString_includesId() {
        assertEquals("QuicStreamId(7)", QuicStreamId(7).toString())
    }
}
