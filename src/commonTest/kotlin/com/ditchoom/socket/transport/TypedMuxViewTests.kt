package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ByteStreamMux
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [TypedMuxView] — the generic single-codec [com.ditchoom.buffer.flow.StreamMux]
 * view over a raw [ByteStreamMux]. Uses a pre-stocked fake raw mux backed by [MemoryTransport]
 * pipes, so the view's composition logic (codec wrapping + [MuxIdentified] id recovery) is
 * exercised on every target without a real transport.
 */
private class FakeByteStreamMux(
    private val openBidi: ArrayDeque<ByteStream> = ArrayDeque(),
    private val openUni: ArrayDeque<ByteSink> = ArrayDeque(),
    private val acceptBidi: ArrayDeque<ByteStream> = ArrayDeque(),
    private val acceptUni: ArrayDeque<ByteSource> = ArrayDeque(),
) : ByteStreamMux {
    override suspend fun openBidirectional(): ByteStream = openBidi.removeFirst()

    override suspend fun openUnidirectional(): ByteSink = openUni.removeFirst()

    override suspend fun acceptBidirectional(): ByteStream = acceptBidi.removeFirst()

    override suspend fun acceptUnidirectional(): ByteSource = acceptUni.removeFirst()
}

/** A raw stream that carries a transport-assigned id, like `QuicByteStream` does. */
private class IdentifiedByteStream(
    delegate: ByteStream,
    override val muxStreamId: Long,
) : ByteStream by delegate,
    MuxIdentified

class TypedMuxViewTests {
    private val config =
        TransportConfig(readPolicy = ReadPolicy.Bounded(5.seconds), writePolicy = WritePolicy.Bounded(5.seconds))

    @Test
    fun openBidirectional_wrapsWithCodecAndRecoversStreamId() =
        runTest {
            val (local, peer) = MemoryTransport.createPair()
            val mux =
                TypedMuxView(FakeByteStreamMux(openBidi = ArrayDeque(listOf(IdentifiedByteStream(local, 8L)))), TestStringCodec, config)

            val conn = mux.openBidirectional()
            assertEquals(8L, conn.id, "Connection.id must recover the MuxIdentified stream id")

            conn.send("hello-view")
            assertEquals("hello-view", readOneFramed(peer))

            conn.close()
            peer.close()
        }

    @Test
    fun openBidirectional_defaultsIdToZeroWithoutMuxIdentified() =
        runTest {
            val (local, peer) = MemoryTransport.createPair()
            val mux = TypedMuxView(FakeByteStreamMux(openBidi = ArrayDeque(listOf(local))), TestStringCodec, config)

            val conn = mux.openBidirectional()
            assertEquals(0L, conn.id, "a raw stream without MuxIdentified falls back to id 0")

            conn.close()
            peer.close()
        }

    @Test
    fun openUnidirectional_encodesThenFinsOnClose() =
        runTest {
            val (local, peer) = MemoryTransport.createPair()
            val mux =
                TypedMuxView(FakeByteStreamMux(openUni = ArrayDeque(listOf(IdentifiedByteStream(local, 2L)))), TestStringCodec, config)

            val sender = mux.openUnidirectional()
            assertEquals(2L, sender.id)

            sender.send("uni-one")
            sender.send("uni-two")
            sender.close() // FIN

            assertEquals("uni-one", readOneFramed(peer))
            assertEquals("uni-two", readOneFramed(peer))
            assertIs<ReadResult.End>(peer.read(5.seconds), "Sender.close() must FIN so the peer reads End")

            peer.close()
        }

    @Test
    fun acceptBidirectional_decodesFromPeer() =
        runTest {
            val (accepted, peer) = MemoryTransport.createPair()
            val mux = TypedMuxView(FakeByteStreamMux(acceptBidi = ArrayDeque(listOf(accepted))), TestStringCodec, config)

            val conn = mux.acceptBidirectional()
            writeOneFramed(peer, "hi-from-peer")
            assertEquals("hi-from-peer", conn.receive().first())

            conn.close()
            peer.close()
        }

    @Test
    fun acceptUnidirectional_receivesUntilFin() =
        runTest {
            val (accepted, peer) = MemoryTransport.createPair()
            val mux = TypedMuxView(FakeByteStreamMux(acceptUni = ArrayDeque(listOf(accepted))), TestStringCodec, config)

            val receiver = mux.acceptUnidirectional()
            writeOneFramed(peer, "recv-a")
            writeOneFramed(peer, "recv-b")
            peer.close() // FIN → flow completes

            assertEquals(listOf("recv-a", "recv-b"), receiver.receive().toList())
        }

    /** Read one length-prefixed string directly from the raw peer side. */
    private suspend fun readOneFramed(raw: ByteStream): String {
        val frame = raw.read(5.seconds)
        check(frame is ReadResult.Data) { "Expected data frame, got $frame" }
        val length = frame.buffer.readShort().toInt() and 0xFFFF
        val s = frame.buffer.readString(length)
        frame.buffer.freeIfNeeded()
        return s
    }

    /** Encode one length-prefixed string and write it directly to the raw peer side. */
    private suspend fun writeOneFramed(
        raw: ByteStream,
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
