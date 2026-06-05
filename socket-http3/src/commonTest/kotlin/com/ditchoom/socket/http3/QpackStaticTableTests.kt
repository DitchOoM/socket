package com.ditchoom.socket.http3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class QpackStaticTableTests {
    @Test
    fun hasExactly99Entries() {
        assertEquals(99, QpackStaticTable.size)
        assertEquals(99, QpackStaticTable.entries.size)
    }

    @Test
    fun spotCheck_knownIndices() {
        assertEquals(QpackHeaderField(":authority", ""), QpackStaticTable.entry(0))
        assertEquals(QpackHeaderField(":path", "/"), QpackStaticTable.entry(1))
        assertEquals(QpackHeaderField(":method", "CONNECT"), QpackStaticTable.entry(15))
        assertEquals(QpackHeaderField(":method", "GET"), QpackStaticTable.entry(17))
        assertEquals(QpackHeaderField(":status", "103"), QpackStaticTable.entry(24))
        assertEquals(QpackHeaderField(":status", "200"), QpackStaticTable.entry(25))
        assertEquals(QpackHeaderField("user-agent", ""), QpackStaticTable.entry(95))
        assertEquals(QpackHeaderField("x-frame-options", "sameorigin"), QpackStaticTable.entry(98))
    }

    @Test
    fun preservesExactCasingAndSpacing() {
        // The transcription-risky entries the targeted RFC re-fetch pinned down.
        assertEquals("text/html; charset=utf-8", QpackStaticTable.entry(52).value) // space after ';'
        assertEquals("text/plain;charset=utf-8", QpackStaticTable.entry(54).value) // no space after ';'
        assertEquals("max-age=31536000; includesubdomains", QpackStaticTable.entry(57).value)
        assertEquals("gzip, deflate, br", QpackStaticTable.entry(31).value)
        assertEquals("FALSE", QpackStaticTable.entry(73).value)
        assertEquals("TRUE", QpackStaticTable.entry(74).value)
        assertEquals("get", QpackStaticTable.entry(76).value)
        assertEquals("script-src 'none'; object-src 'none'; base-uri 'none'", QpackStaticTable.entry(85).value)
    }

    @Test
    fun entry_outOfRange_throws() {
        assertFailsWith<IllegalArgumentException> { QpackStaticTable.entry(-1) }
        assertFailsWith<IllegalArgumentException> { QpackStaticTable.entry(99) }
    }

    @Test
    fun findExact_returnsMatchingIndex() {
        assertEquals(17, QpackStaticTable.findExact(":method", "GET"))
        assertEquals(25, QpackStaticTable.findExact(":status", "200"))
        assertEquals(98, QpackStaticTable.findExact("x-frame-options", "sameorigin"))
        assertEquals(0, QpackStaticTable.findExact(":authority", ""))
        assertNull(QpackStaticTable.findExact(":method", "BREW")) // value not present
        assertNull(QpackStaticTable.findExact("x-made-up", "1")) // name not present
    }

    @Test
    fun findName_returnsLowestIndexForName() {
        assertEquals(15, QpackStaticTable.findName(":method")) // first :method is CONNECT @15
        assertEquals(24, QpackStaticTable.findName(":status")) // first :status is 103 @24, not the @63 block
        assertEquals(44, QpackStaticTable.findName("content-type")) // first content-type @44
        assertEquals(33, QpackStaticTable.findName("access-control-allow-headers")) // @33 before @75
        assertNull(QpackStaticTable.findName("x-made-up"))
    }
}
