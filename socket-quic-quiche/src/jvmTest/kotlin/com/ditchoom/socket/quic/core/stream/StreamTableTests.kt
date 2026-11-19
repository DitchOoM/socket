package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StreamRecvResult
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.testing.testLoopMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class StreamTableTests {
    private val api = StubQuicheApi()
    private val q = QuicheConnRef(api, QuicheConn(1L))

    private fun blockOf(ids: Int): StreamsBlock =
        StreamsBlock.bytesFor(ids).let { bytes -> StreamsBlock(q, nativeBytes(bytes).address, bytes) }

    private val table =
        StreamTable(
            q,
            StreamIo(q),
            QuicRole.Client,
            testLoopMemory().drains,
            blockOf(StreamsBlock.IDS),
        )

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
    fun moreReadableAndWritableStreamsThanTheBlockHoldsAreAllServedInOneWake() {
        val small =
            StreamTable(q, StreamIo(q), QuicRole.Client, testLoopMemory().drains, blockOf(1))
        val readers = listOf(4L, 8L).map { id -> Counting(Retry.Again).also { small.entry(QuicStreamId(id)).parkReader(it) } }
        val writers =
            listOf(0L, 12L).map { id ->
                small.wantWritable(QuicStreamId(id))
                Counting(Retry.Settled).also { small.entry(QuicStreamId(id)).parkWriter(it) }
            }
        api.readableStreams.addAll(listOf(4L, 8L))
        api.writableStreams.addAll(listOf(0L, 12L))
        small.wake()
        assertEquals(listOf(1, 1), readers.map { it.retries }, "each readable stream once, through quiche's iterator")
        assertEquals(listOf(1, 1), writers.map { it.retries }, "each writable stream once: one from the block, one after it")
        assertEquals(0, api.writableStreams.size)
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
            table.sendEnded(QuicStreamId(id), Side.Reset)
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
        table.waitToOpen(open)
        table.endAll(ConnEnd.IdleTimeout)
        assertEquals(listOf<ConnEnd>(ConnEnd.IdleTimeout), reader.ended)
        assertEquals(listOf<ConnEnd>(ConnEnd.IdleTimeout), open.ended)
        assertSame(ConnEnd.IdleTimeout, assertIs<Streams.Ended>(table.state).end)
    }
}
