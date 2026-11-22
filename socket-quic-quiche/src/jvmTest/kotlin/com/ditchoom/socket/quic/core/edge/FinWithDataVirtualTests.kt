package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StreamSendStep
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * A write that ends our side carries the FIN with its last bytes, on a real core client and server over a 60 ms round
 * trip, on virtual time: a request that fits a datagram leaves as one datagram, raw or typed, and a write larger than
 * quiche takes at once ends the stream only with its last byte.
 */
class FinWithDataVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun aRequestThatFitsADatagramLeavesAsOneDatagramCarryingItsFin() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val stream = pair.client.connection.openCoreStream()
            val got = Received(pair)
            launch { got.readAll(serverSide.acceptStream()) }
            val from = pair.now()
            var wrote = -1
            launch { wrote = assertIs<Finished.All>(stream.writeAndFinish(bytes(REQUEST), 1.hours)).bytes }
            pair.runUntil { got.endedAt >= 0 }

            assertEquals(REQUEST, wrote)
            assertEquals(REQUEST.toLong(), got.bytes, "the server read the whole request, then its end")
            val request = pair.sentBy(End.Client, from).filter { it.at < got.endedAt }
            assertEquals(1, request.size, "the request and its FIN left in one datagram: ${request.map { it.bytes.size }}")
        }

    @Test
    fun aTypedValueSentWithTheFinishLeavesAsOneDatagramCarryingItsFin() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val stream = pair.client.connection.openTypedStream(NoteCodec)
            val got = Received(pair)
            launch { got.readAll(serverSide.acceptStream()) }
            val from = pair.now()
            var sent = false
            launch {
                stream.sendAndFinish(NOTE)
                sent = true
            }
            pair.runUntil { got.endedAt >= 0 && sent }

            assertEquals((NoteCodec.HEADER + 4 + NOTE.text.length).toLong(), got.bytes)
            val request = pair.sentBy(End.Client, from).filter { it.at < got.endedAt }
            assertEquals(1, request.size, "the frame and its FIN left in one datagram: ${request.map { it.bytes.size }}")
        }

    /**
     * quiche takes what its flow window allows of a write larger than the window; the core offers the FIN with every
     * piece, and quiche keeps it only with the piece that takes the last byte: the peer reads every byte before the end.
     */
    @Test
    fun aWriteLargerThanQuicheTakesAtOnceEndsTheStreamOnlyWithItsLastByte() =
        VirtualPair.with(options) { pair ->
            val sends = ArrayList<StreamSend>()
            // The server offered the pair's first client: taken, so the next offer is the recording client's.
            pair.accept()
            val client = pair.reconnect(options) { RecordingStreamSends(it, sends) }
            var established = false
            launch {
                client.connection.established()
                established = true
            }
            var serverSide: CoreConnection? = null
            launch { serverSide = pair.accept() }
            pair.runUntil { established && serverSide != null }
            val stream = client.connection.openCoreStream()
            var wrote = -1
            launch { wrote = assertIs<Finished.All>(stream.writeAndFinish(bytes(LARGE), 1.hours)).bytes }
            pair.runFor(1.seconds)
            assertEquals(-1, wrote, "nobody reads yet: the write waits with bytes quiche has not taken")

            val got = Received(pair)
            launch { got.readAll(checkNotNull(serverSide).acceptStream()) }
            pair.runUntil { got.endedAt >= 0 && wrote >= 0 }

            assertEquals(LARGE, wrote)
            assertEquals(LARGE.toLong(), got.bytes, "every byte arrived before the end")
            val pieces = sends.filter { it.offered > 0 }
            assertTrue(pieces.size > 1, "quiche took the write in pieces: $pieces")
            assertTrue(pieces.all { it.fin }, "every piece offered the FIN: $pieces")
            assertEquals(LARGE, pieces.sumOf { maxOf(it.took, 0) }, "the pieces quiche took add up to the write: $pieces")
        }

    /** One stream send the client's core asked of quiche: what it offered, whether with the FIN, and what quiche took. */
    private data class StreamSend(
        val offered: Int,
        val fin: Boolean,
        val took: Int,
    )

    private class RecordingStreamSends(
        private val inner: QuicheApi,
        private val sends: MutableList<StreamSend>,
    ) : QuicheApi by inner {
        override fun connStreamSendStep(
            conn: QuicheConn,
            streamId: QuicStreamId,
            buf: Long,
            bufLen: Int,
            fin: Boolean,
        ): StreamSendStep =
            inner.connStreamSendStep(conn, streamId, buf, bufLen, fin).also { step ->
                sends += StreamSend(bufLen, fin, step.fold(took = { it }, full = { 0 }, refused = { code, _ -> code }))
            }
    }

    /** What the server read of one stream, and when, on the pair's clock, its end arrived. */
    private class Received(
        private val pair: VirtualPair,
    ) {
        var bytes = 0L
        var endedAt = -1L

        suspend fun readAll(stream: QuicByteStream) {
            while (stream.read(1.hours) { bytes += it.remaining() } is ScopedRead.Data<*>) Unit
            endedAt = pair.now()
        }
    }

    private fun bytes(size: Int) =
        BufferFactory.Default.allocate(size).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        const val REQUEST = 512
        val NOTE = Note(1, "n".repeat(REQUEST))

        /** Larger than a stream's initial flow window. */
        const val LARGE = 4 * 1024 * 1024
    }
}
