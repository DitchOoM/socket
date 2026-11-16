package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class StreamTableTests {
    private val api = StubQuicheApi()
    private val q = QuicheConnRef(api, QuicheConn(1L))
    private val table = StreamTable(q, StreamIo(q), QuicRole.Client)

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
        assertEquals(Opened.Opened, table.tryOpen())
        assertEquals(QuicStreamId(0L), table.lastOpened())
        assertEquals(Opened.Opened, table.tryOpen())
        assertEquals(QuicStreamId(4L), table.lastOpened())
    }

    @Test
    fun anOpenAtThePeersLimitDoesNotSpendTheId() {
        api.connStreamSendResult = QUICHE_ERR_STREAM_LIMIT
        assertEquals(Opened.AtLimit, table.tryOpen())
        api.connStreamSendResult = null
        table.tryOpen()
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
    fun theEndFailsEveryWaiterAndWaitingOpenAndStaysRecorded() {
        val reader = Counting(Retry.Again)
        val open = Counting(Retry.Again)
        table.entry(QuicStreamId(0L)).parkReader(reader)
        table.waitToOpen(open)
        table.endAll(ConnEnd.IdleTimeout)
        assertEquals(listOf<ConnEnd>(ConnEnd.IdleTimeout), reader.ended)
        assertEquals(listOf<ConnEnd>(ConnEnd.IdleTimeout), open.ended)
        assertSame(ConnEnd.IdleTimeout, assertIs<Streams.Ended>(table.state).end)
    }
}
