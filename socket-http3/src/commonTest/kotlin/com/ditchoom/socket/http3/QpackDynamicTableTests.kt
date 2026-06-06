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
    fun insertIfEvictableEvictsOnlyWhenEveryVictimIsEvictable() {
        // capacity 70 holds two 34-octet entries; inserting a third must evict the oldest (absolute 0).
        val t = table(70)
        t.insert("a", "1") // abs 0
        t.insert("b", "2") // abs 1

        // Victim (abs 0) refused by the predicate ⇒ no insert, table untouched.
        val refused = t.insertIfEvictable("c", "3") { it.absoluteIndex != 0L }
        assertNull(refused)
        assertEquals(2L, t.insertCount, "refused insert does not advance insertCount")
        assertEquals(68L, t.size)
        assertTrue(t.isLive(0), "refused insert evicts nothing")

        // Victim allowed ⇒ evicts abs 0 and inserts at abs 2.
        val accepted = t.insertIfEvictable("c", "3") { true }
        assertEquals(2L, accepted)
        assertNull(t.getByAbsolute(0), "oldest evicted once allowed")
        assertEquals("c", t.getByAbsolute(2)?.name)
    }

    @Test
    fun insertIfEvictableFitsWithoutEvictionNeverConsultsPredicate() {
        val t = table(1000)
        t.insert("a", "1")
        // Plenty of room ⇒ predicate must not even be asked (no eviction needed).
        val abs = t.insertIfEvictable("b", "2") { error("predicate consulted though no eviction was needed") }
        assertEquals(1L, abs)
        assertTrue(t.isLive(0) && t.isLive(1))
    }

    @Test
    fun insertIfEvictableRejectsEntryLargerThanCapacity() {
        val t = table(40)
        assertNull(t.insertIfEvictable("name", "a-very-long-value-that-exceeds-capacity") { true })
        assertEquals(0L, t.insertCount)
    }

    @Test
    fun maxEntriesIsMaxCapacityOver32() {
        assertEquals(31L, QpackDynamicTable(1000).maxEntries) // floor(1000/32)
        assertEquals(0L, QpackDynamicTable(0).maxEntries)
    }

    @Test
    fun isDrainingMarksOldestEntriesWithinTheReserve() {
        // capacity 512 ⇒ draining reserve = 512/8 = 64 octets. Each entry is 34 octets, so only the
        // single oldest entry (cumulative 34 ≤ 64) is draining; the next (cumulative 68 > 64) is not.
        val t = table(512)
        t.insert("a", "1") // abs 0, cumulative 34
        t.insert("b", "2") // abs 1, cumulative 68
        t.insert("c", "3") // abs 2, cumulative 102
        assertTrue(t.isDraining(0), "oldest entry is within the draining reserve")
        assertFalse(t.isDraining(1), "second entry is past the reserve")
        assertFalse(t.isDraining(2))
        assertFalse(t.isDraining(5), "a non-live index is never draining")
    }

    @Test
    fun isDrainingIsFalseWhenTableUnusable() {
        // capacity 0 (never raised) ⇒ no draining region at all.
        assertFalse(QpackDynamicTable(512).isDraining(0))
    }

    @Test
    fun freshTableHasZeroCapacityUntilRaised() {
        val t = QpackDynamicTable(1000) // not yet setCapacity
        assertEquals(0L, t.capacity)
        assertNull(t.insert("a", "1"), "capacity 0 ⇒ nothing fits")
    }
}
