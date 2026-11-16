package com.ditchoom.socket.quic.core.observe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class FlightRecorderTests {
    private class ListSink : FlightSink {
        val bytes = ArrayList<Byte>()

        override fun put(byte: Byte) {
            bytes.add(byte)
        }
    }

    private class ListSource(
        private val bytes: List<Byte>,
    ) : FlightSource {
        private var at = 0

        override fun hasMore(): Boolean = at < bytes.size

        override fun next(): Byte = bytes[at++]
    }

    private fun dump(ring: FlightRing): List<Byte> = ListSink().also { FlightCodec.write(ring, it) }.bytes

    private fun restore(bytes: List<Byte>): FlightRing = assertIs<Recording.Restored>(FlightCodec.read(ListSource(bytes))).ring

    private fun assertSameRecords(
        expected: FlightRing,
        actual: FlightRing,
    ) {
        assertEquals(expected.size, actual.size)
        for (i in 0 until expected.size) {
            assertEquals(expected.kindAt(i), actual.kindAt(i), "kind $i")
            assertEquals(expected.nanosAt(i), actual.nanosAt(i), "nanos $i")
            assertEquals(expected.subAt(i), actual.subAt(i), "sub $i")
            assertEquals(expected.localAt(i), actual.localAt(i), "local $i")
            assertEquals(expected.peerAt(i), actual.peerAt(i), "peer $i")
            assertEquals(expected.lengthAt(i), actual.lengthAt(i), "length $i")
            assertEquals(expected.aAt(i), actual.aAt(i), "a $i")
            assertEquals(expected.bAt(i), actual.bAt(i), "b $i")
        }
        assertEquals(expected.addressCount, actual.addressCount)
        for (id in 0 until expected.addressCount) {
            assertEquals(expected.familyOf(id), actual.familyOf(id))
            assertEquals(expected.portOf(id), actual.portOf(id))
            assertEquals(expected.hiOf(id), actual.hiOf(id))
            assertEquals(expected.loOf(id), actual.loOf(id))
        }
    }

    @Test
    fun everyKindOfRecordSurvivesTheCodec() {
        val ring = FlightRing(32)
        val wifi = ring.bindAddress(4, 4433, 0L, 0x0A000001L)
        val cell = ring.bindAddress(6, 4434, -5L, Long.MIN_VALUE)
        val server = ring.bindAddress(4, 443, 0L, 0x0A000002L)
        ring.datagram(FlightKind.DatagramOut, 1_000L, 1200, wifi, server, tag = -0x1234_5678_9ABCL, head = 0xC000_0001_0814_0000UL.toLong())
        ring.datagram(FlightKind.DatagramIn, 1_500L, 60, cell, server, tag = 77L, head = 0x4000_0000_0000_0000L)
        ring.timer(FlightKind.TimerArm, 1_600L, dueNanos = 9_999_999_999L)
        ring.timer(FlightKind.TimerFire, 9_999L, dueNanos = 9_000L)
        ring.op(FlightKind.OpSubmit, 2_000L, op = 3, streamId = 4L, length = 70_000L)
        ring.op(FlightKind.OpComplete, 2_100L, op = 3, streamId = 4L, length = 70_000L)
        ring.event(FlightKind.NetworkEvent, 2_200L, sub = 1, local = cell, peer = server, a = 5L, b = 0L)
        ring.event(FlightKind.QuichePathEvent, 2_300L, sub = 4, local = wifi, peer = server, a = 0L, b = 0L)
        ring.ended(2_400L, end = 2, errorCode = 0x1AL)

        val restored = restore(dump(ring))

        assertSameRecords(ring, restored)
        assertEquals(FlightKind.QuichePathEvent, restored.kindAt(7))
        assertEquals(4433, restored.portOf(wifi))
        assertEquals(6, restored.familyOf(cell))
    }

    @Test
    fun aFullRingKeepsTheNewestRecordsAndCountsWhatItLost() {
        val ring = FlightRing(4)
        repeat(10) { ring.timer(FlightKind.TimerArm, it * 10L, dueNanos = it + 1L) }

        assertEquals(4, ring.size)
        assertEquals(6L, ring.dropped)
        assertEquals(60L, ring.nanosAt(0))
        assertEquals(90L, ring.nanosAt(3))
        assertSameRecords(ring, restore(dump(ring)))
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
    fun aRecordedDatagramCostsAboutTwentyFiveBytes() {
        val ring = FlightRing(100)
        val a = ring.bindAddress(4, 1, 0L, 1L)
        val b = ring.bindAddress(4, 2, 0L, 2L)
        repeat(
            100,
        ) { ring.datagram(FlightKind.DatagramOut, it * 1_000L, 1200, a, b, tag = 0x1122_3344_5566_7788L, head = 0x0102_0304_0506_0708L) }
        assertEquals(true, dump(ring).size < 100 * 28 + 64)
    }

    @Test
    fun aRecordingThatIsNotOneOrIsCutShortIsTyped() {
        assertEquals(Recording.NotARecording, FlightCodec.read(ListSource(listOf(1, 2, 3, 4))))
        val ring = FlightRing(4)
        ring.datagram(FlightKind.DatagramIn, 5L, 10, 0, 0, tag = 1L, head = 2L)
        val whole = dump(ring)
        assertEquals(Recording.Truncated, FlightCodec.read(ListSource(whole.dropLast(3))))
        assertEquals(Recording.Truncated, FlightCodec.read(ListSource(emptyList())))
    }

    /**
     * What the replay harness does with a client recording and a server recording: match each datagram one end sent to
     * the datagram the other end received by (length, tail bytes), and read delivery, loss and delay off the pair.
     */
    @Test
    fun aClientAndAServerRecordingGiveEveryDatagramItsFate() {
        val client = FlightRing(16)
        val server = FlightRing(16)
        val c1 = client.bindAddress(4, 5000, 0L, 1L)
        val c2 = client.bindAddress(4, 6000, 0L, 1L)
        val s = client.bindAddress(4, 443, 0L, 2L)
        // The client sends three datagrams, the second after a path change; the network drops the third.
        client.datagram(FlightKind.DatagramOut, 1_000L, 1200, c1, s, tag = 11L, head = 1L)
        client.datagram(FlightKind.DatagramOut, 2_000L, 1200, c2, s, tag = 22L, head = 1L)
        client.datagram(FlightKind.DatagramOut, 3_000L, 1200, c2, s, tag = 33L, head = 1L)
        val ss = server.bindAddress(4, 443, 0L, 2L)
        val sc1 = server.bindAddress(4, 5000, 0L, 1L)
        val sc2 = server.bindAddress(4, 6000, 0L, 1L)
        server.datagram(FlightKind.DatagramIn, 51_000L, 1200, ss, sc1, tag = 11L, head = 1L)
        server.datagram(FlightKind.DatagramIn, 52_500L, 1200, ss, sc2, tag = 22L, head = 1L)

        val fates = ArrayList<String>()
        var offset = 0L
        var calibrated = false
        for (i in 0 until client.size) {
            var delivered = -1
            for (j in 0 until server.size) {
                if (server.aAt(j) == client.aAt(i) && server.lengthAt(j) == client.lengthAt(i)) delivered = j
            }
            if (delivered < 0) {
                fates.add("dropped")
                continue
            }
            if (!calibrated) {
                offset = server.nanosAt(delivered) - client.nanosAt(i)
                calibrated = true
            }
            fates.add(
                "delivered +${server.nanosAt(delivered) - client.nanosAt(i) - offset} to port ${server.portOf(server.peerAt(delivered))}",
            )
        }

        assertEquals(listOf("delivered +0 to port 5000", "delivered +500 to port 6000", "dropped"), fates)
    }
}
