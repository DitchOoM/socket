package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One pass reads a flooded socket up to its bound and never past it. A batch receive is lent its connection's queue
 * only when a pass serves the connection, after the pass's reads, and the receive asks again from its own thread: one
 * that asks too late for a pass's serve is lent at the next, after a second pass's reads. Two passes' reads fit in the
 * default [DatagramOptions.recvQueueLen], so a receiver one pass late loses nothing.
 */
class PassReadBoundTests {
    /** A socket that always holds more: every receive takes [PER_READ] datagrams, short of a full batch. */
    private class Flooded : DatagramBatchIo by FakeIo() {
        override fun receive(into: ReceiveBatch): ReceiveBatchResult {
            repeat(PER_READ) { into.received(into.take() as ReceiveBuffer, LENGTH, PEER, ArrivedOn.Unreported) }
            return ReceiveBatchResult.Got
        }
    }

    @Test
    fun twoPassesOfAFloodedSocketReadNoMoreDatagramsThanAReceiveQueueHolds() {
        val rig = ConnRig(io = Flooded())
        rig.pass()
        val first = rig.loop.counts.datagramsIn
        rig.pass()
        val read = rig.loop.counts.datagramsIn
        val depth = DatagramOptions().recvQueueLen
        assertTrue(read <= depth, "two passes read $read datagrams of a flood ($first in the first); a receive queue holds $depth")
        assertTrue(read > depth - 2 * BATCH, "two passes read $read datagrams of a flood, short of $depth by more than a batch each")
    }

    private companion object {
        /** Reads of 60 never land on a bound that is a power of two: the read that would cross it is not begun. */
        const val PER_READ = 60
        const val BATCH = 64
        const val LENGTH = 1_000
        val PEER = PathKey(4, 443, 0L, 0x7F000001L)
    }
}
