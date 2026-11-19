package com.ditchoom.socket.quic.core.observe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The flight ring: preallocated records that keep the newest and count what they lost. */
class FlightRecorderTests {
    @Test
    fun aFullRingKeepsTheNewestRecordsAndCountsWhatItLost() {
        val ring = FlightRing(4)
        repeat(10) { ring.timer(FlightKind.TimerArm, it * 10L, dueNanos = it + 1L) }

        assertEquals(4, ring.size)
        assertEquals(6L, ring.dropped)
        assertEquals(60L, ring.nanosAt(0))
        assertEquals(90L, ring.nanosAt(3))
        assertEquals(7L, ring.aAt(0))
    }

    @Test
    fun anAddressIsRecordedOnceAndKeepsItsId() {
        val ring = FlightRing(4)
        val first = ring.bindAddress(4, 1, 0L, 2L)
        val other = ring.bindAddress(4, 1, 0L, 3L)
        assertNotEquals(first, other)
        assertEquals(first, ring.bindAddress(4, 1, 0L, 2L))
        assertEquals(2, ring.addressCount)
    }

    @Test
    fun aDatagramRecordKeepsItsFields() {
        val ring = FlightRing(2)
        val peer = ring.bindAddress(4, 443, 0L, 1L)
        ring.datagram(FlightKind.DatagramIn, 5L, 1200, 0, peer, tag = 77L, head = 88L)
        assertEquals(FlightKind.DatagramIn, ring.kindAt(0))
        assertEquals(1200, ring.lengthAt(0))
        assertEquals(peer, ring.peerAt(0))
        assertEquals(77L, ring.aAt(0))
        assertEquals(88L, ring.bAt(0))
    }
}
