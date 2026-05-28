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
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.transport.MemoryTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
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
    scope: CoroutineScope,
    private val openStreams: ArrayDeque<QuicByteStream>,
    private val incomingStreams: ArrayDeque<QuicByteStream>,
) : QuicScope,
    CoroutineScope by scope {
    private val incomingChannel = Channel<QuicByteStream>(Channel.UNLIMITED)

    init {
        incomingStreams.forEach { incomingChannel.trySend(it) }
    }

    override suspend fun openStream(): QuicByteStream = openStreams.removeFirst()

    override suspend fun acceptStream(): QuicByteStream = incomingChannel.receive()

    override fun streams(): Flow<QuicByteStream> = incomingChannel.consumeAsFlow()
}

private fun pairOfStreams(
    idA: Long,
    idB: Long,
): Pair<QuicByteStream, QuicByteStream> {
    val (rawA, rawB) = MemoryTransport.createPair()
    return QuicByteStream(QuicStreamId(idA), rawA) to QuicByteStream(QuicStreamId(idB), rawB)
}

class QuicStreamMuxCommonTests {
    private val opts = ConnectionOptions(readTimeout = 5.seconds, writeTimeout = 5.seconds)

    @Test
    fun openBidirectional_propagatesStreamId() =
        runQuicTest {
            val (clientSide, serverSide) = pairOfStreams(idA = 0, idB = 4)
            val mux =
                QuicStreamMux(
                    connection = FakeQuicScope(this, ArrayDeque(listOf(clientSide)), ArrayDeque()),
                    codec = MuxStringCodec,
                    options = opts,
                )

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

    @Test
    fun acceptBidirectional_returnsFromIncomingChannel() =
        runQuicTest {
            val (raw0, raw1) = pairOfStreams(idA = 1, idB = 5)
            val mux =
                QuicStreamMux(
                    connection = FakeQuicScope(this, ArrayDeque(), ArrayDeque(listOf(raw0))),
                    codec = MuxStringCodec,
                    options = opts,
                )

            val accepted = mux.acceptBidirectional()
            assertEquals(1L, accepted.id)

            // The peer side writes a framed message; the mux-wrapped accepted side decodes it.
            sendOneFromRawSide(raw1, "hi-from-peer")
            val msg = accepted.receive().first()
            assertEquals("hi-from-peer", msg)

            accepted.close()
            raw1.close()
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
