package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.use
import com.ditchoom.socket.transport.ReadResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Echo tests using [MockQuicConnection] — validates ByteStream data exchange
 * without quiche or network access. All assertions use [contentEquals] on
 * buffers — no ByteArray or String intermediaries.
 */
class QuicEchoServerTests {
    /**
     * Write expected string into a buffer and compare with received buffer.
     * Zero-copy comparison via [com.ditchoom.buffer.ReadBuffer.contentEquals].
     */
    private fun assertBufferEquals(
        expected: String,
        actual: com.ditchoom.buffer.ReadBuffer,
        factory: BufferFactory,
    ) {
        factory.allocate(expected.length).use { expectedBuf ->
            expectedBuf.writeString(expected, Charset.UTF8)
            expectedBuf.resetForRead()
            // Don't call actual.resetForRead() — transport already reset it.
            // Double-reset causes flip() to set limit=0.
            assertTrue(
                expectedBuf.contentEquals(actual),
                "Buffer content mismatch: expected '$expected' (${expectedBuf.remaining()} bytes), " +
                    "got ${actual.remaining()} bytes",
            )
        }
    }

    @Test
    fun quicEcho_singleStream() =
        runTest {
            val factory = TrackingBufferFactory()
            val conn = MockQuicConnection()
            val stream = conn.openStream()
            val peerStream = conn.peerStreams[stream.streamId]!!

            factory.allocate(11).use { sendBuf ->
                sendBuf.writeString("hello quic!", Charset.UTF8)
                sendBuf.resetForRead()
                stream.write(sendBuf, 5.seconds)
            }

            val serverResult = peerStream.read(5.seconds)
            assertIs<ReadResult.Data>(serverResult)
            peerStream.write(serverResult.buffer, 5.seconds)

            val clientResult = stream.read(5.seconds)
            assertIs<ReadResult.Data>(clientResult)
            assertBufferEquals("hello quic!", clientResult.buffer, factory)

            clientResult.buffer.freeIfNeeded()
            serverResult.buffer.freeIfNeeded()
            stream.close()
            peerStream.close()
            conn.close()
            factory.assertNoLeaks()
        }

    @Test
    fun quicEcho_multipleMessages() =
        runTest {
            val factory = TrackingBufferFactory()
            val conn = MockQuicConnection()
            val stream = conn.openStream()
            val peerStream = conn.peerStreams[stream.streamId]!!

            val messages = listOf("first", "second", "third", "fourth", "fifth")

            for (msg in messages) {
                factory.allocate(msg.length).use { buf ->
                    buf.writeString(msg, Charset.UTF8)
                    buf.resetForRead()
                    stream.write(buf, 5.seconds)
                }

                val serverResult = peerStream.read(5.seconds)
                assertIs<ReadResult.Data>(serverResult)
                peerStream.write(serverResult.buffer, 5.seconds)

                val clientResult = stream.read(5.seconds)
                assertIs<ReadResult.Data>(clientResult)
                assertBufferEquals(msg, clientResult.buffer, factory)

                clientResult.buffer.freeIfNeeded()
                serverResult.buffer.freeIfNeeded()
            }

            stream.close()
            peerStream.close()
            conn.close()
            factory.assertNoLeaks()
        }

    @Test
    fun quicEcho_multipleStreams() =
        runTest {
            val factory = BufferFactory.Default
            val conn = MockQuicConnection()

            val streams = (0 until 3).map { conn.openStream() }
            val peerStreams = streams.map { conn.peerStreams[it.streamId]!! }

            streams.forEachIndexed { i, stream ->
                val msg = "stream-$i"
                val buf = factory.allocate(msg.length)
                buf.writeString(msg, Charset.UTF8)
                buf.resetForRead()
                stream.write(buf, 5.seconds)

                val serverResult = peerStreams[i].read(5.seconds)
                assertIs<ReadResult.Data>(serverResult)
                peerStreams[i].write(serverResult.buffer, 5.seconds)

                val clientResult = stream.read(5.seconds)
                assertIs<ReadResult.Data>(clientResult)
                assertBufferEquals(msg, clientResult.buffer, factory)
            }

            streams.forEach { it.close() }
            peerStreams.forEach { it.close() }
            conn.close()
        }

    @Test
    fun quicTransport_worksWithCodecConnection() =
        runTest {
            val conn = MockQuicConnection()
            val stream = conn.openStream()
            val peerStream = conn.peerStreams[stream.streamId]!!

            assertTrue(stream.isOpen)
            assertIs<QuicByteStream>(stream)

            stream.close()
            peerStream.close()
            conn.close()
        }
}
