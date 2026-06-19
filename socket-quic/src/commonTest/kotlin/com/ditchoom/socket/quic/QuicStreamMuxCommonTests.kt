package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.MemoryTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Common (cross-platform) unit tests for [QuicStreamMux].
 *
 * Complements [QuicStreamMuxTests] (jvmTest, real QUIC over loopback).
 * Uses a fake [QuicScope] backed by [MemoryTransport] so the mux composition
 * logic is exercised on every target — JVM, K/N, JS, wasmJs.
 */
private object MuxStringCodec : Codec<String> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): String {
        val length = buffer.readShort().toInt() and 0xFFFF
        return buffer.readString(length)
    }

    override fun encode(
        buffer: WriteBuffer,
        value: String,
        context: EncodeContext,
    ) {
        val bytes = value.encodeToByteArray()
        buffer.writeShort(bytes.size.toShort())
        buffer.writeBytes(bytes)
    }

    override fun wireSize(
        value: String,
        context: EncodeContext,
    ): WireSize = WireSize.BackPatch

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult {
        if (stream.available() < baseOffset + 2) return PeekResult.NeedsMoreData
        val length = stream.peekShort(baseOffset).toInt() and 0xFFFF
        return PeekResult.Complete(2 + length)
    }
}

/**
 * Test [QuicScope] that hands out pre-stocked [QuicByteStream]s from in-memory
 * pipes. Exercises [QuicStreamMux]'s open/accept composition without bringing
 * in quiche FFI or real network I/O.
 */
private class FakeQuicScope(
    parent: CoroutineScope,
    private val openStreams: ArrayDeque<QuicByteStream>,
    private val incomingStreams: ArrayDeque<QuicByteStream>,
    private val openUniStreams: ArrayDeque<QuicByteStream> = ArrayDeque(),
) : QuicScope {
    // Own a child job so the mux's demux router — a structured child of this scope — can be cancelled
    // at end-of-test, exactly as `withQuicConnection` cancels the connection scope on block exit.
    // Without this, the router's infinite `streams().collect` would keep the test's parent scope from
    // completing and the test would hang until its wall-clock backstop. [close] models the connection
    // closing.
    private val job = Job(parent.coroutineContext[Job])
    override val coroutineContext = parent.coroutineContext + job
    override val bufferFactory: BufferFactory = BufferFactory.Default
    private val incomingChannel = Channel<QuicByteStream>(Channel.UNLIMITED)

    init {
        incomingStreams.forEach { incomingChannel.trySend(it) }
    }

    override suspend fun openStream(): QuicByteStream = openStreams.removeFirst()

    override suspend fun openUniStream(): QuicByteStream = openUniStreams.removeFirst()

    override suspend fun acceptStream(): QuicByteStream = incomingChannel.receive()

    override fun streams(): Flow<QuicByteStream> = incomingChannel.consumeAsFlow()

    /** Models the connection closing: cancels the scope, force-stopping the mux router. */
    fun close() {
        job.cancel()
    }
}

private fun pairOfStreams(
    idA: Long,
    idB: Long,
): Pair<QuicByteStream, QuicByteStream> {
    val (rawA, rawB) = MemoryTransport.createPair()
    return QuicByteStream(QuicStreamId(idA), rawA) to QuicByteStream(QuicStreamId(idB), rawB)
}

class QuicStreamMuxCommonTests {
    private val opts = TransportConfig(readPolicy = ReadPolicy.Bounded(5.seconds), writePolicy = WritePolicy.Bounded(5.seconds))

    /**
     * Runs [block] against a [QuicStreamMux] over a pre-stocked [FakeQuicScope], then closes the scope
     * so the mux's demux router stops (mirrors `withQuicMux` cancelling the connection scope on exit).
     */
    private suspend fun CoroutineScope.muxTest(
        open: List<QuicByteStream> = emptyList(),
        incoming: List<QuicByteStream> = emptyList(),
        uni: List<QuicByteStream> = emptyList(),
        block: suspend (QuicStreamMux<String>) -> Unit,
    ) {
        val fake = FakeQuicScope(this, ArrayDeque(open), ArrayDeque(incoming), ArrayDeque(uni))
        try {
            block(QuicStreamMux(fake, MuxStringCodec, opts))
        } finally {
            fake.close()
        }
    }

    @Test
    fun openBidirectional_propagatesStreamId() =
        runQuicTest {
            val (clientSide, serverSide) = pairOfStreams(idA = 0, idB = 4)
            muxTest(open = listOf(clientSide)) { mux ->
                val conn = mux.openBidirectional()
                assertEquals(0L, conn.id, "Connection.id should mirror QUIC stream id")

                // Round-trip through the underlying memory pipe to prove the codec
                // is wired through the mux layer correctly.
                conn.send("hello-mux")
                val received = readOneFromRawSide(serverSide)
                assertEquals("hello-mux", received)

                conn.close()
                serverSide.close()
            }
        }

    @Test
    fun acceptBidirectional_returnsFromIncomingChannel() =
        runQuicTest {
            val (raw0, raw1) = pairOfStreams(idA = 1, idB = 5)
            muxTest(incoming = listOf(raw0)) { mux ->
                val accepted = mux.acceptBidirectional()
                assertEquals(1L, accepted.id)

                // The peer side writes a framed message; the mux-wrapped accepted side decodes it.
                sendOneFromRawSide(raw1, "hi-from-peer")
                val msg = accepted.receive().first()
                assertEquals("hi-from-peer", msg)

                accepted.close()
                raw1.close()
            }
        }

    @Test
    fun openUnidirectional_encodesThenFinsOnClose() =
        runQuicTest {
            // Client-initiated unidirectional stream id 2 (0b10): client-initiated (bit0=0), uni (bit1=1).
            val (sendSide, peerSide) = pairOfStreams(idA = 2, idB = 2)
            muxTest(uni = listOf(sendSide)) { mux ->
                val sender = mux.openUnidirectional()
                assertEquals(2L, sender.id, "Sender.id should mirror the uni QUIC stream id")

                sender.send("uni-one")
                sender.send("uni-two")
                sender.close() // FIN

                // Peer sees both framed messages, then a clean end-of-send (FIN → ReadResult.End).
                assertEquals("uni-one", readOneFromRawSide(peerSide))
                assertEquals("uni-two", readOneFromRawSide(peerSide))
                assertIs<ReadResult.End>(peerSide.read(5.seconds), "close() must FIN so the peer reads End")

                peerSide.close()
            }
        }

    @Test
    fun acceptUnidirectional_receivesUntilFin() =
        runQuicTest {
            // Server-initiated unidirectional stream id 3 (0b11) — what a client accepts from the peer.
            val (incoming, peer) = pairOfStreams(idA = 3, idB = 3)
            muxTest(incoming = listOf(incoming)) { mux ->
                val receiver = mux.acceptUnidirectional()

                // Peer writes two framed messages then FINs; the receiver flow yields both and completes.
                sendOneFromRawSide(peer, "recv-a")
                sendOneFromRawSide(peer, "recv-b")
                peer.close() // FIN → flow completes

                assertEquals(listOf("recv-a", "recv-b"), receiver.receive().toList())
            }
        }

    @Test
    fun accept_routesByDirection() =
        runQuicTest {
            // One incoming uni (id 3) and one incoming bidi (id 1), queued uni-first to prove the demux
            // router routes by direction rather than by arrival order.
            val (uniIn, uniPeer) = pairOfStreams(idA = 3, idB = 3)
            val (biIn, biPeer) = pairOfStreams(idA = 1, idB = 5)
            muxTest(incoming = listOf(uniIn, biIn)) { mux ->
                // The uni stream must surface through acceptUnidirectional, the bidi through acceptBidirectional.
                val receiver = mux.acceptUnidirectional()
                val conn = mux.acceptBidirectional()
                assertEquals(1L, conn.id, "bidi stream id 1 must route to acceptBidirectional")

                sendOneFromRawSide(uniPeer, "via-uni")
                uniPeer.close()
                assertEquals(listOf("via-uni"), receiver.receive().toList())

                sendOneFromRawSide(biPeer, "via-bidi")
                assertEquals("via-bidi", conn.receive().first())

                conn.close()
                biPeer.close()
            }
        }

    /** Read one length-prefixed string directly from a raw byte stream. */
    private suspend fun readOneFromRawSide(raw: QuicByteStream): String {
        val frame = raw.read(5.seconds)
        check(frame is ReadResult.Data) { "Expected data frame, got $frame" }
        val length = frame.buffer.readShort().toInt() and 0xFFFF
        val s = frame.buffer.readString(length)
        frame.buffer.freeIfNeeded()
        return s
    }

    /** Encode one length-prefixed string and write it directly to a raw byte stream. */
    private suspend fun sendOneFromRawSide(
        raw: QuicByteStream,
        value: String,
    ) {
        val bytes = value.encodeToByteArray()
        val buf = BufferFactory.Default.allocate(2 + bytes.size)
        buf.writeShort(bytes.size.toShort())
        buf.writeBytes(bytes)
        buf.resetForRead()
        raw.write(buf, 5.seconds)
        buf.freeIfNeeded()
    }
}
