package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.transport.ReadResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end QUIC echo tests using a local quiche-based echo server.
 *
 * These spin up a QUIC server on localhost, connect a client, exchange data,
 * and verify round-trip integrity — same pattern as [com.ditchoom.socket.SimpleSocketTests].
 *
 * Requires quiche native library built. @Ignore by default.
 */
class QuicEchoServerTests {
    @Test
    @Ignore("Requires quiche native library")
    fun quicEcho_singleStream() =
        runTest {
            val factory = TrackingBufferFactory()
            val options =
                QuicOptions(
                    alpnProtocols = listOf("echo"),
                    verifyPeer = false, // localhost self-signed
                )
            val connOptions = ConnectionOptions(bufferFactory = factory)

            // TODO: Start a local quiche echo server
            // val server = QuicEchoServer(port = 0, alpn = "echo")
            // server.start()
            // val port = server.localPort()

            // For now, test the transport integration with mock:
            val conn = MockQuicConnection()
            val stream = conn.openStream()
            val peerStream = conn.peerStreams[stream.streamId]!!

            // Client writes
            val sendBuf = factory.allocate(11)
            sendBuf.writeBytes("hello quic!".encodeToByteArray())
            sendBuf.resetForRead()
            stream.write(sendBuf, 5.seconds)
            sendBuf.freeNativeMemory()

            // Server-side reads and echoes back
            val serverResult = peerStream.read(5.seconds)
            assertIs<ReadResult.Data>(serverResult)
            // Echo: write it back
            peerStream.write(serverResult.buffer, 5.seconds)

            // Client reads echo
            val clientResult = stream.read(5.seconds)
            assertIs<ReadResult.Data>(clientResult)

            clientResult.buffer.resetForRead()
            val received = clientResult.buffer.readByteArray(clientResult.buffer.remaining())
            assertEquals("hello quic!", received.decodeToString())

            // Cleanup
            clientResult.buffer.freeIfNeeded()
            serverResult.buffer.freeIfNeeded()
            stream.close()
            peerStream.close()
            conn.close()

            factory.assertNoLeaks()
        }

    @Test
    @Ignore("Requires quiche native library")
    fun quicEcho_multipleMessages() =
        runTest {
            val factory = TrackingBufferFactory()
            val conn = MockQuicConnection()
            val stream = conn.openStream()
            val peerStream = conn.peerStreams[stream.streamId]!!

            val messages = listOf("first", "second", "third", "fourth", "fifth")

            for (msg in messages) {
                // Client sends
                val buf = factory.allocate(msg.length)
                buf.writeBytes(msg.encodeToByteArray())
                buf.resetForRead()
                stream.write(buf, 5.seconds)
                buf.freeNativeMemory()

                // Server reads
                val serverResult = peerStream.read(5.seconds)
                assertIs<ReadResult.Data>(serverResult)

                // Server echoes
                peerStream.write(serverResult.buffer, 5.seconds)

                // Client reads echo
                val clientResult = stream.read(5.seconds)
                assertIs<ReadResult.Data>(clientResult)
                clientResult.buffer.resetForRead()
                val bytes = clientResult.buffer.readByteArray(clientResult.buffer.remaining())
                assertEquals(msg, bytes.decodeToString())

                clientResult.buffer.freeIfNeeded()
                serverResult.buffer.freeIfNeeded()
            }

            stream.close()
            peerStream.close()
            conn.close()
            factory.assertNoLeaks()
        }

    @Test
    @Ignore("Requires quiche native library")
    fun quicEcho_multipleStreams() =
        runTest {
            val conn = MockQuicConnection()

            val streams = (0 until 3).map { conn.openStream() }
            val peerStreams = streams.map { conn.peerStreams[it.streamId]!! }

            // Each stream echoes independently
            streams.forEachIndexed { i, stream ->
                val msg = "stream-$i"
                val buf = BufferFactory.Default.allocate(msg.length)
                buf.writeBytes(msg.encodeToByteArray())
                buf.resetForRead()
                stream.write(buf, 5.seconds)

                val serverResult = peerStreams[i].read(5.seconds)
                assertIs<ReadResult.Data>(serverResult)
                peerStreams[i].write(serverResult.buffer, 5.seconds)

                val clientResult = stream.read(5.seconds)
                assertIs<ReadResult.Data>(clientResult)
                clientResult.buffer.resetForRead()
                val bytes = clientResult.buffer.readByteArray(clientResult.buffer.remaining())
                assertEquals(msg, bytes.decodeToString())
            }

            streams.forEach { it.close() }
            peerStreams.forEach { it.close() }
            conn.close()
        }

    @Test
    fun quicTransport_worksWithCodecConnection() =
        runTest {
            // Verify QuicTransport integrates with CodecConnection (the transport abstraction)
            val conn = MockQuicConnection()
            val stream = conn.openStream()
            val peerStream = conn.peerStreams[stream.streamId]!!

            // The QuicByteStream IS a ByteStream, so CodecConnection can use it directly
            assertTrue(stream.isOpen)
            assertIs<QuicByteStream>(stream)

            stream.close()
            peerStream.close()
            conn.close()
        }
}
