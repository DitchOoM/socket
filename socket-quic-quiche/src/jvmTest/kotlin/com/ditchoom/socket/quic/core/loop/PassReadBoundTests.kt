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
 * One pass reads a flooded socket up to its bound and never past it. A connection's receive queue holds a default
 * [DatagramOptions.recvQueueLen] datagrams, and a batch receive waiting for the loop is lent them only when the pass
 * serves the connection, after all its reads: a pass that read past the queue's depth dropped datagrams while the
 * receiver waited.
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
    fun aFloodedSocketsPassReadsNoMoreDatagramsThanAReceiveQueueHolds() {
        val rig = ConnRig(io = Flooded())
        rig.pass()
        val read = rig.loop.counts.datagramsIn
        val depth = DatagramOptions().recvQueueLen
        assertTrue(read <= depth, "one pass read $read datagrams of a flood; a receive queue holds $depth")
        assertTrue(read > depth - BATCH, "one pass read $read datagrams of a flood, short of the bound $depth by a batch or more")
    }

    private companion object {
        /** Reads of 60 never land on a bound of 1024: the read that crosses it takes 60 past what is left. */
        const val PER_READ = 60
        const val BATCH = 64
        const val LENGTH = 1_000
        val PEER = PathKey(4, 443, 0L, 0x7F000001L)
    }
}
