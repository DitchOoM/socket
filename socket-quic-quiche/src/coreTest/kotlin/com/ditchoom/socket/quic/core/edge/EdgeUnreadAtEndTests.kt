package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicUnreadAtClose
import com.ditchoom.socket.quic.core.conn.HeldReads
import com.ditchoom.socket.quic.core.life.CloseOutcome
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.stream.InHand
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.trace.TraceCapture
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A read that was handed bytes when the connection ended keeps its stream unread until its caller takes them, so a block
 * waiting on [QuicUnreadAtClose.AllRead] is not cancelled between the hand-off and the read's return.
 */
class EdgeUnreadAtEndTests {
    @Test
    fun aStreamWhoseBytesAReadStillHoldsStaysUnreadUntilTheReadReturns() {
        val edge = EdgeState(BufferFactory.network().allocate(ALPN), TraceCapture.Off, TicketHome.NotKept)
        val read = Read(InHand.Yes)
        edge.handed.add(STREAM, read)
        edge.ended(BY_PEER, NOTHING_HELD, NOT_ASKED)
        assertEquals<QuicUnreadAtClose>(QuicUnreadAtClose.Unread(setOf(STREAM)), edge.unread.value)
        read.taken = InHand.No
        edge.returned(STREAM)
        assertEquals<QuicUnreadAtClose>(QuicUnreadAtClose.AllRead, edge.unread.value)
    }

    @Test
    fun aReadWhoseCallerTookItsBytesBeforeTheEndWasPublishedLeavesNothingUnread() {
        val edge = EdgeState(BufferFactory.network().allocate(ALPN), TraceCapture.Off, TicketHome.NotKept)
        val read = Read(InHand.Yes)
        edge.handed.add(STREAM, read)
        // The caller took its bytes and returned before the end was published, finding nothing to leave yet.
        read.taken = InHand.No
        edge.returned(STREAM)
        edge.ended(BY_PEER, NOTHING_HELD, NOT_ASKED)
        assertEquals<QuicUnreadAtClose>(QuicUnreadAtClose.AllRead, edge.unread.value)
    }

    private class Read(
        var taken: InHand,
    ) : Waiter {
        override fun retry(table: StreamTable): Retry = Retry.Settled

        override fun connectionEnded(end: ConnEnd) = Unit

        override fun inHand(): InHand = taken
    }

    private companion object {
        const val ALPN = 32
        val STREAM = QuicStreamId(1L)
        val NOTHING_HELD = HeldReads(emptyMap(), emptySet())
        val BY_PEER = ConnEnd.ByPeer(QuicError.NoError)
        val NOT_ASKED = CloseOutcome.EndedFirst(BY_PEER)
    }
}
