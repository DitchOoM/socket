package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import kotlin.test.Test
import kotlin.test.assertEquals

/** The backlog holds its first chunk of slots from the start and takes more only while Initials wait past it. */
class InitialBacklogTests {
    private class CountingChunks(
        private val refuseAfter: Int = Int.MAX_VALUE,
    ) : BacklogChunks {
        var made = 0

        override fun make(): ChunkMade =
            if (made >= refuseAfter) {
                ChunkMade.Refused
            } else {
                made++
                ChunkMade.Made(nativeBytes(InitialBacklog.CHUNK_BYTES))
            }
    }

    private val datagram = nativeBytes(1500)

    private fun from(n: Int) = PathKey(4, 1000 + n, 0L, 0x7F000001L)

    private fun InitialBacklog.holdNumbered(n: Int): Held {
        datagram.putInt(0, n)
        return hold(datagram, 1200, from(n), ArrivedOn.Unreported)
    }

    private fun InitialBacklog.dropChecked(n: Int) {
        assertEquals(n, oldest().intAt(0), "held in order")
        assertEquals(1200, oldestLength())
        assertEquals(from(n), oldestFrom())
        dropOldest()
    }

    @Test
    fun aBacklogStartsWithOneChunkAndGrowsAChunkAtATimeWhileInitialsWait() {
        val chunks = CountingChunks()
        val backlog = InitialBacklog(capacity = 4 * InitialBacklog.SLOTS_PER_CHUNK, chunks)
        assertEquals(1, chunks.made, "one chunk from the start")
        repeat(InitialBacklog.SLOTS_PER_CHUNK) { assertEquals(Held.Yes, backlog.holdNumbered(it)) }
        assertEquals(1, chunks.made, "a full first chunk takes no second")
        assertEquals(Held.Yes, backlog.holdNumbered(InitialBacklog.SLOTS_PER_CHUNK))
        assertEquals(2, chunks.made, "the next Initial takes a second chunk")
        repeat(InitialBacklog.SLOTS_PER_CHUNK + 1) { backlog.dropChecked(it) }
        assertEquals(0, backlog.size)
    }

    @Test
    fun aChunkEmptiedIsReusedSoAWaveTheSizeOfTheLastTakesNoMore() {
        val chunks = CountingChunks()
        val backlog = InitialBacklog(capacity = 4 * InitialBacklog.SLOTS_PER_CHUNK, chunks)
        var n = 0
        repeat(5) {
            val wave = 2 * InitialBacklog.SLOTS_PER_CHUNK + 3
            val first = n
            repeat(wave) { assertEquals(Held.Yes, backlog.holdNumbered(n++)) }
            repeat(wave) { backlog.dropChecked(first + it) }
        }
        assertEquals(3, chunks.made, "the first wave's chunks carry every later wave")
    }

    @Test
    fun theBacklogIsFullAtItsCapacityOrWhenNoChunkCanBeMade() {
        val bounded = InitialBacklog(capacity = InitialBacklog.SLOTS_PER_CHUNK + 1, CountingChunks())
        repeat(InitialBacklog.SLOTS_PER_CHUNK + 1) { assertEquals(Held.Yes, bounded.holdNumbered(it)) }
        assertEquals(Held.Full, bounded.holdNumbered(-1), "past its capacity")

        val refused = InitialBacklog(capacity = 4 * InitialBacklog.SLOTS_PER_CHUNK, CountingChunks(refuseAfter = 1))
        repeat(InitialBacklog.SLOTS_PER_CHUNK) { assertEquals(Held.Yes, refused.holdNumbered(it)) }
        assertEquals(Held.Full, refused.holdNumbered(-1), "no chunk could be made")
        refused.dropChecked(0)
        assertEquals(Held.Full, refused.holdNumbered(-1), "a slot freed in the first chunk is behind the newest: still full")
        repeat(InitialBacklog.SLOTS_PER_CHUNK - 1) { refused.dropChecked(it + 1) }
        assertEquals(Held.Yes, refused.holdNumbered(7), "an emptied chunk starts over")
    }

    @Test
    fun aDatagramLargerThanASlotIsNotHeld() {
        val backlog = InitialBacklog(capacity = 8, CountingChunks())
        assertEquals(Held.Full, backlog.hold(nativeBytes(2048), InitialBacklog.SLOT_BYTES + 1, from(0), ArrivedOn.Unreported))
    }
}
