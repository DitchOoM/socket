package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.SendBatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** The loop's batch is lent a serve at a time; what a socket refused leaves it with its endpoint. */
class SharedBatchTests {
    private val limits = BatchLimits(maxDatagrams = 4, maxDatagramSize = 1500)

    private fun batch() = SendBatch(nativeBytes(limits.maxDatagrams * limits.maxDatagramSize), limits)

    private class Spares(
        private val make: () -> SpareMade,
    ) : SpareSource {
        var asked = 0

        override fun make(): SpareMade {
            asked++
            return make.invoke()
        }
    }

    @Test
    fun refusedDatagramsStayWithTheirEndpointAndTheLoopCollectsIntoASpareUntilTheyAreOut() {
        val first = batch()
        val spares = Spares { SpareMade.Made(batch()) }
        val loop = LoopSend(first, spares)
        val a = SharedBatch(loop)
        val b = SharedBatch(loop)
        a.batch().commit(100, SendTarget.ConnectedPeer)
        a.served()
        assertEquals(true, a.holds(), "the refused datagram is a's")
        assertSame(first, a.batch(), "a keeps the batch it collected into")
        assertNotSame(first, b.batch(), "b collects into a spare")
        assertEquals(0, b.batch().pending, "and finds none of a's datagrams there")
        first.took(1)
        a.served()
        assertEquals(false, a.holds())
        assertSame(loop.batch(), a.batch(), "a collects into the loop's batch again")
        b.batch().commit(100, SendTarget.ConnectedPeer)
        b.served()
        assertSame(first, loop.batch(), "the batch a gave back is the next spare")
        assertEquals(1, spares.asked, "one spare made for the two refusals")
        assertEquals(2, loop.held(), "the first batch and one spare")
    }

    @Test
    fun withNoSpareTheRefusedDatagramsAreDroppedAndTheLoopKeepsItsBatch() {
        val first = batch()
        val loop = LoopSend(first, Spares { SpareMade.Refused })
        val a = SharedBatch(loop)
        a.batch().commit(100, SendTarget.ConnectedPeer)
        a.batch().commit(100, SendTarget.ConnectedPeer)
        a.served()
        assertEquals(false, a.holds())
        assertSame(first, loop.batch())
        assertEquals(0, first.pending, "the next endpoint finds the batch empty")
        assertEquals(2L, loop.dropped)
    }

    @Test
    fun anEndpointThatLeavesHoldingDatagramsGivesItsBatchBack() {
        val first = batch()
        val loop = LoopSend(first, Spares { SpareMade.Made(batch()) })
        val a = SharedBatch(loop)
        a.batch().commit(100, SendTarget.ConnectedPeer)
        a.served()
        val spare = loop.batch()
        a.left()
        assertEquals(false, a.holds())
        assertEquals(0, first.pending, "what a held is dropped")
        val b = SharedBatch(loop)
        b.batch().commit(100, SendTarget.ConnectedPeer)
        b.served()
        assertSame(spare, b.batch(), "b keeps the batch it collected into")
        assertSame(first, loop.batch(), "the batch a gave back serves the loop")
        assertEquals(2, loop.held(), "no further spare made")
    }
}
