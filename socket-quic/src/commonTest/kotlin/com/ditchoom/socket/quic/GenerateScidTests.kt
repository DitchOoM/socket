package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The entropy seam of RFC_DETERMINISTIC_SIMULATION.md §3.1: [generateScid] takes an injectable
 * [Random] so the simulation harness can seed it and get reproducible connection IDs, while the
 * default keeps production behaviour (platform default source) unchanged.
 */
class GenerateScidTests {
    private val factory = BufferFactory.deterministic()

    @Test
    fun sameSeed_producesIdenticalScids() {
        val a = generateScid(factory, Random(42))
        val b = generateScid(factory, Random(42))
        try {
            assertEquals(QUIC_ISSUED_CONN_ID_LEN, a.remaining(), "scid must be the issued CID length")
            assertTrue(a.contentEquals(b), "same-seed Randoms must produce byte-identical connection IDs")
        } finally {
            a.freeNativeMemory()
            b.freeNativeMemory()
        }
    }

    @Test
    fun differentSeeds_produceDifferentScids() {
        val a = generateScid(factory, Random(1))
        val b = generateScid(factory, Random(2))
        try {
            assertFalse(a.contentEquals(b), "different seeds must not collide on a 128-bit connection ID")
        } finally {
            a.freeNativeMemory()
            b.freeNativeMemory()
        }
    }

    @Test
    fun defaultRandom_producesFreshScidsPerCall() {
        // The no-arg production path: consecutive CIDs from the default source must differ
        // (128 bits — a collision here means the entropy plumbing broke, not bad luck).
        val a = generateScid(factory)
        val b = generateScid(factory)
        try {
            assertFalse(a.contentEquals(b), "default-random connection IDs must differ per call")
        } finally {
            a.freeNativeMemory()
            b.freeNativeMemory()
        }
    }

    @Test
    fun issuedIds_are16Bytes_ofWhich128BitsAreRandom() {
        // 16, not the RFC 9000 maximum of 20: Wireshark cannot link a migrated path to a connection
        // whose id is exactly 20 bytes (see QUIC_ISSUED_CONN_ID_LEN).
        assertEquals(16, QUIC_ISSUED_CONN_ID_LEN)
        assertTrue(QUIC_ISSUED_CONN_ID_LEN < QUIC_MAX_CONN_ID_LEN)
        val scid = generateScid(factory, Random(7))
        try {
            assertEquals(16, scid.remaining(), "an issued id must be 16 bytes on the wire")
            val expected = Random(7)
            assertEquals(expected.nextLong(), scid.readLong(), "bytes 0..7 come straight from the random source")
            assertEquals(expected.nextLong(), scid.readLong(), "bytes 8..15 come straight from the random source")
            assertEquals(0, scid.remaining())
        } finally {
            scid.freeNativeMemory()
        }
    }
}
