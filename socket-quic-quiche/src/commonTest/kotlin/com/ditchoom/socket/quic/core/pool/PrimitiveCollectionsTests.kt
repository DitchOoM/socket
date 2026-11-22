package com.ditchoom.socket.quic.core.pool

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The core's primitive-keyed collections against the standard library's, under the same random operations. */
class PrimitiveCollectionsTests {
    private class Box(
        val n: Long,
    )

    @Test
    fun aLongMapAgreesWithAHashMapThroughGrowthCollisionsAndRemovals() {
        val ours = LongObjectMap(Box(-1))
        val reference = HashMap<Long, Box>()
        val random = Random(7)
        repeat(20_000) {
            // Keys from a small range collide and recur; stream ids step by four, so do the same here.
            val key = random.nextLong(0, 600) * 4
            when (random.nextInt(4)) {
                0, 1 -> {
                    val box = Box(key)
                    ours[key] = box
                    reference[key] = box
                }
                2 -> assertEquals(reference.remove(key) != null, ours.remove(key), "remove $key")
                else -> {
                    val absent = Box(-1)
                    assertSame(reference[key] ?: absent, ours.getOrDefault(key, absent), "get $key")
                }
            }
            assertEquals(reference.size, ours.size)
        }
        val missing = Box(-1)
        for (key in 0L until 2_400L) {
            assertSame(reference[key] ?: missing, ours.getOrDefault(key, missing), "get $key")
            assertEquals(reference.containsKey(key), ours.containsKey(key), "contains $key")
        }
        val seen = ArrayList<Box>()
        ours.forEachValue { seen += it }
        assertEquals(reference.values.toSet(), seen.toSet())
        assertEquals(reference.size, seen.size)
    }

    @Test
    fun aLongMapHoldsKeyZeroAndNegativeKeys() {
        val map = LongObjectMap(Box(-1))
        map[0L] = Box(0)
        map[Long.MIN_VALUE] = Box(1)
        map[-1L] = Box(2)
        assertEquals(3, map.size)
        assertEquals(0L, map.getOrDefault(0L, Box(9)).n)
        assertEquals(1L, map.getOrDefault(Long.MIN_VALUE, Box(9)).n)
        assertTrue(map.remove(0L))
        assertFalse(map.containsKey(0L))
        assertEquals(2L, map.getOrDefault(-1L, Box(9)).n)
    }

    @Test
    fun aLongSetAddsOnceAndRemovesWhatItHolds() {
        val set = LongSet()
        assertFalse(set.isNotEmpty())
        set.add(4L)
        set.add(4L)
        set.add(0L)
        assertTrue(set.isNotEmpty())
        assertTrue(set.remove(4L))
        assertFalse(set.remove(4L))
        assertTrue(set.remove(0L))
        assertFalse(set.isNotEmpty())
    }

    @Test
    fun aLongQueueIsFirstInFirstOutThroughGrowthAndWrap() {
        val queue = LongQueue()
        val reference = ArrayDeque<Long>()
        val random = Random(3)
        repeat(10_000) { n ->
            if (random.nextInt(3) < 2) {
                queue.addLast(n.toLong())
                reference.addLast(n.toLong())
            } else {
                val taken = ArrayList<Long>()
                queue.drain { taken += it }
                assertEquals(reference.toList(), taken)
                reference.clear()
            }
            assertEquals(reference.isNotEmpty(), queue.isNotEmpty())
        }
    }

    @Test
    fun anIntStackIsLastInFirstOut() {
        val stack = IntStack()
        assertEquals(-7, stack.popOr { -7 })
        repeat(100) { stack.push(it) }
        for (n in 99 downTo 0) assertEquals(n, stack.popOr { -7 })
        assertEquals(-7, stack.popOr { -7 })
    }
}
