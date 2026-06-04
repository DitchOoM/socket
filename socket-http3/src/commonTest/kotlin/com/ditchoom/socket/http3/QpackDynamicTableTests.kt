package com.ditchoom.socket.http3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC 9204 §3.2 dynamic-table behaviour: size accounting, eviction, absolute indexing, lookup. */
class QpackDynamicTableTests {
    private fun table(maxCapacity: Long): QpackDynamicTable = QpackDynamicTable(maxCapacity).apply { setCapacity(maxCapacity) }

    @Test
    fun entrySizeIsNamePlusValuePlus32() {
        // "a"(1) + "bc"(2) + 32 = 35.
        assertEquals(35L, qpackEntrySize("a", "bc"))
    }

    @Test
    fun insertAssignsMonotonicAbsoluteIndicesAndTracksSize() {
        val t = table(1000)
        assertEquals(0L, t.insert("a", "1")) // size 34
        assertEquals(1L, t.insert("b", "2")) // size 34
        assertEquals(2L, t.insertCount)
        assertEquals(68L, t.size)
        assertEquals("a", t.getByAbsolute(0)?.name)
        assertEquals("2", t.getByAbsolute(1)?.value)
    }

    @Test
    fun insertEvictsOldestToFit() {
        // capacity 70 holds two 34-octet entries; a third evicts the oldest (absolute 0).
        val t = table(70)
        t.insert("a", "1")
        t.insert("b", "2")
        val third = t.insert("c", "3")
        assertEquals(2L, third)
        assertNull(t.getByAbsolute(0), "oldest entry evicted")
        assertEquals("b", t.getByAbsolute(1)?.name)
        assertEquals("c", t.getByAbsolute(2)?.name)
        assertEquals(3L, t.insertCount, "evicted entries still spend their absolute index")
        assertEquals(68L, t.size)
    }

    @Test
    fun entryLargerThanCapacityIsRejected() {
        val t = table(40) // one 34-octet entry fits; a bigger one never does
        assertNull(t.insert("name", "a-very-long-value-that-exceeds-capacity"))
        assertEquals(0L, t.insertCount)
        assertEquals(0L, t.size)
    }

    @Test
    fun setCapacityEvictsDownAndRejectsAboveMax() {
        val t = table(1000)
        t.insert("a", "1") // 34
        t.insert("b", "2") // 34
        assertTrue(t.setCapacity(40), "shrink within max")
        assertNull(t.getByAbsolute(0), "shrink evicts oldest until size <= capacity")
        assertEquals(34L, t.size)
        assertFalse(t.setCapacity(2000), "capacity above maxCapacity is rejected")
        assertEquals(40L, t.capacity, "rejected setCapacity leaves capacity unchanged")
    }

    @Test
    fun getByAbsoluteOutOfRangeIsNull() {
        val t = table(1000)
        t.insert("a", "1")
        assertNull(t.getByAbsolute(5), "not yet inserted")
        assertNull(t.getByAbsolute(-1), "below the table")
        assertFalse(t.isLive(1))
        assertTrue(t.isLive(0))
    }

    @Test
    fun findPrefersNewestMatch() {
        val t = table(1000)
        t.insert("x", "old") // abs 0
        t.insert("x", "new") // abs 1
        assertEquals(1L, t.findName("x"), "newest matching name")
        assertEquals(0L, t.findExact("x", "old"))
        assertEquals(1L, t.findExact("x", "new"))
        assertNull(t.findExact("x", "absent"))
        assertNull(t.findName("y"))
    }

    @Test
    fun maxEntriesIsMaxCapacityOver32() {
        assertEquals(31L, QpackDynamicTable(1000).maxEntries) // floor(1000/32)
        assertEquals(0L, QpackDynamicTable(0).maxEntries)
    }

    @Test
    fun freshTableHasZeroCapacityUntilRaised() {
        val t = QpackDynamicTable(1000) // not yet setCapacity
        assertEquals(0L, t.capacity)
        assertNull(t.insert("a", "1"), "capacity 0 ⇒ nothing fits")
    }
}
