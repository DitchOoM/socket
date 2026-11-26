package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StreamRecvResult
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.testing.testLoopMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class StreamTableTests {
    private val api = StubQuicheApi()
    private val q = QuicheConnRef(api, QuicheConn(1L))
    private val table = StreamTable(q, StreamIo(q, QuicAppErrorCode(0L)), QuicRole.Client, testLoopMemory().drains, EarlyCopy.Off)
    private val send = (table.state as Streams.Open).send

    private class Counting(
        private val answer: Retry,
    ) : Waiter {
        var retries = 0
        val ended = ArrayList<ConnEnd>()

        override fun retry(table: StreamTable): Retry {
            retries++
            return answer
        }

        override fun connectionEnded(end: ConnEnd) {
            ended += end
        }
    }

    @Test
    fun opensMaterialiseTheNextClientBidiStreamWithAZeroLengthSend() {
        assertEquals(Opened.Opened, table.tryOpen(StreamKind.Bidirectional, send))
        assertEquals(QuicStreamId(0L), table.lastOpened())
        assertEquals(Opened.Opened, table.tryOpen(StreamKind.Bidirectional, send))
        assertEquals(QuicStreamId(4L), table.lastOpened())
    }

    @Test
    fun anOpenAtThePeersLimitDoesNotSpendTheId() {
        api.connStreamSendResult = QUICHE_ERR_STREAM_LIMIT
        assertEquals(Opened.AtLimit, table.tryOpen(StreamKind.Bidirectional, send))
        api.connStreamSendResult = null
        table.tryOpen(StreamKind.Bidirectional, send)
        assertEquals(QuicStreamId(0L), table.lastOpened())
    }

    @Test
    fun aReadableStreamThePeerOpenedIsHandedUpOnceAndItsReaderRetried() {
        val reader = Counting(Retry.Again)
        table.entry(QuicStreamId(4L)).parkReader(reader)
        api.readableStreams.addAll(listOf(1L, 4L))
        table.wake()
        table.wake()
        val incoming = ArrayList<QuicStreamId>()
        table.takeIncoming { incoming += it }
        assertEquals(listOf(QuicStreamId(1L)), incoming)
        assertEquals(2, reader.retries)
    }

    @Test
    fun aWriterIsRetriedOnlyWhenItAskedAndItsStreamIsWritable() {
        val writer = Counting(Retry.Settled)
        table.entry(QuicStreamId(0L)).parkWriter(writer)
        api.writableStreams.addLast(0L)
        table.wake()
        assertEquals(0, writer.retries)
        table.wantWritable(QuicStreamId(0L))
        api.writableStreams.addLast(0L)
        table.wake()
        assertEquals(1, writer.retries)
        assertSame(Nobody, table.entry(QuicStreamId(0L)).writer)
    }

    @Test
    fun aStreamThePeerStoppedIsAskedItsCapacityWithNobodyWritingSoItsEntryAndQuichesStreamGo() {
        val id = QuicStreamId(1L)
        api.readableStreams.addLast(id.id)
        table.wake()
        table.takeIncoming { }
        table.receiveEnded(id, Side.Finished)
        assertEquals(1, table.liveStreams())
        api.streamCapacity = QUICHE_ERR_STREAM_STOPPED.toLong()
        api.writableStreams.addLast(id.id)
        table.wake()
        assertEquals(0, table.liveStreams(), "the peer's stop ended our side, and the spent entry went")
    }

    @Test
    fun aSpentStreamReportedReadableAgainIsDrainedNotHandedUpAsANewOne() {
        api.readableStreams.addAll(listOf(5L, 1L))
        table.wake()
        val incoming = ArrayList<QuicStreamId>()
        table.takeIncoming { incoming += it }
        assertEquals(listOf(QuicStreamId(5L), QuicStreamId(1L)), incoming, "the peer's streams, out of order, each once")
        for (id in listOf(1L, 5L)) {
            table.receiveEnded(QuicStreamId(id), Side.Stopped)
            table.sendEnded(QuicStreamId(id), Side.Reset(QuicAppErrorCode(0L)))
        }
        assertEquals(0, table.liveStreams())
        api.readableStreams.clear()
        api.readableStreams.addAll(listOf(1L, 5L, 9L))
        api.streamRecvResult = StreamRecvResult.Reset(QuicAppErrorCode(7L))
        table.wake()
        incoming.clear()
        table.takeIncoming { incoming += it }
        assertEquals(listOf(QuicStreamId(9L)), incoming, "only the stream never seen is new")
    }

    @Test
    fun theEndFailsEveryWaiterAndWaitingOpenAndStaysRecorded() {
        val reader = Counting(Retry.Again)
        val open = Counting(Retry.Again)
        table.entry(QuicStreamId(0L)).parkReader(reader)
        table.opens.lane(StreamKind.Bidirectional).wait(open)
        table.endAll(ConnEnd.IdleTimeout)
        assertEquals(listOf<ConnEnd>(ConnEnd.IdleTimeout), reader.ended)
        assertEquals(listOf<ConnEnd>(ConnEnd.IdleTimeout), open.ended)
        assertSame(ConnEnd.IdleTimeout, assertIs<Streams.Ended>(table.state).end)
    }

    @Test
    fun theTwoLowBitsOfAnIdAreItsShapeForEachRole() {
        val client = (0L..7L).map { shapeOf(QuicStreamId(it), QuicRole.Client) }
        val server = (0L..7L).map { shapeOf(QuicStreamId(it), QuicRole.Server) }
        val c = listOf(StreamShape.LocalBidirectional, StreamShape.PeerBidirectional, StreamShape.SendOnly, StreamShape.ReceiveOnly)
        val s = listOf(StreamShape.PeerBidirectional, StreamShape.LocalBidirectional, StreamShape.ReceiveOnly, StreamShape.SendOnly)
        assertEquals(c + c, client)
        assertEquals(s + s, server)
        assertEquals(listOf(0L, 2L), listOf(StreamKind.Bidirectional, StreamKind.Unidirectional).map { it.firstLocal(QuicRole.Client) })
        assertEquals(listOf(1L, 3L), listOf(StreamKind.Bidirectional, StreamKind.Unidirectional).map { it.firstLocal(QuicRole.Server) })
        for (id in 0L..7L) assertEquals(shapeOf(QuicStreamId(id), QuicRole.Client).kind(), kindOf(QuicStreamId(id)))
    }

    @Test
    fun unidirectionalOpensTakeTheirOwnIdsAndAreLimitedApartFromBidirectionalOnes() {
        assertEquals(Opened.Opened, table.tryOpen(StreamKind.Unidirectional, send))
        assertEquals(QuicStreamId(2L), table.lastOpened())
        assertEquals(Opened.Opened, table.tryOpen(StreamKind.Bidirectional, send))
        assertEquals(QuicStreamId(0L), table.lastOpened())
        assertEquals(Opened.Opened, table.tryOpen(StreamKind.Unidirectional, send))
        assertEquals(QuicStreamId(6L), table.lastOpened())
        api.connStreamSendResult = QUICHE_ERR_STREAM_LIMIT
        assertEquals(Opened.AtLimit, table.tryOpen(StreamKind.Unidirectional, send))
        api.connStreamSendResult = null
        assertEquals(Opened.Opened, table.tryOpen(StreamKind.Unidirectional, send))
        assertEquals(QuicStreamId(10L), table.lastOpened(), "the open at the limit spent no id")
    }

    @Test
    fun aWaitingOpenOfOneKindIsRetriedOnlyByCreditOfThatKind() {
        val uni = Counting(Retry.Settled)
        val bidi = Counting(Retry.Settled)
        api.peerStreamsLeftUni = 0L
        api.peerStreamsLeftBidi = 0L
        table.opens.lane(StreamKind.Unidirectional).wait(uni)
        table.opens.lane(StreamKind.Bidirectional).wait(bidi)
        table.wake()
        assertEquals(0, uni.retries + bidi.retries)
        api.peerStreamsLeftUni = 1L
        table.wake()
        assertEquals(1, uni.retries, "MAX_STREAMS for unidirectional streams retries the waiting uni open")
        assertEquals(0, bidi.retries, "and leaves the bidirectional one waiting")
        assertEquals(1, table.opensWaiting())
    }

    @Test
    fun aSendOnlyStreamIsSpentOnceOurSideEndsAndAReceiveOnlyOneOnceThePeersDoes() {
        table.tryOpen(StreamKind.Unidirectional, send)
        val ours = table.lastOpened()
        assertSame(Side.NotPart, table.entry(ours).received)
        table.sendEnded(ours, Side.Finished)
        assertEquals(0, table.liveStreams(), "our FIN alone spends a stream we only send on")
        assertEquals(true, table.isSpent(ours))

        api.readableStreams.addLast(3L)
        table.wake()
        val incoming = ArrayList<QuicStreamId>()
        table.takeIncoming { incoming += it }
        assertEquals(listOf(QuicStreamId(3L)), incoming, "the peer's unidirectional stream is handed up")
        assertSame(Side.NotPart, table.entry(QuicStreamId(3L)).sent)
        table.receiveEnded(QuicStreamId(3L), Side.Finished)
        assertEquals(0, table.liveStreams(), "the peer's FIN alone spends a stream we only receive on")
    }
}
