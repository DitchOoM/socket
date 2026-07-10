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
            assertEquals(QUIC_MAX_CONN_ID_LEN, a.remaining(), "scid must be the full RFC 9000 max CID length")
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
            assertFalse(a.contentEquals(b), "different seeds must not collide on a 160-bit connection ID")
        } finally {
            a.freeNativeMemory()
            b.freeNativeMemory()
        }
    }

    @Test
    fun defaultRandom_producesFreshScidsPerCall() {
        // The no-arg production path: consecutive CIDs from the default source must differ
        // (160 bits — a collision here means the entropy plumbing broke, not bad luck).
        val a = generateScid(factory)
        val b = generateScid(factory)
        try {
            assertFalse(a.contentEquals(b), "default-random connection IDs must differ per call")
        } finally {
            a.freeNativeMemory()
            b.freeNativeMemory()
        }
    }
}
