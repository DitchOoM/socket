package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.transport.MemoryTransport
import com.ditchoom.socket.transport.ReadResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class QuicByteStreamTests {
    private fun createStream(): Pair<QuicByteStream, com.ditchoom.socket.transport.ByteStream> {
        val (clientSide, serverSide) = MemoryTransport.createPair(BufferFactory.Default)
        val stream = QuicByteStream(QuicStreamId(0), clientSide)
        return stream to serverSide
    }

    // --- Read path ---

    @Test
    fun read_returnsData_whenDataAvailable() =
        runTest {
            val (stream, server) = createStream()
            val buf = BufferFactory.Default.allocate(5)
            buf.writeBytes("hello".encodeToByteArray())
            buf.resetForRead()
            server.write(buf)

            val result = stream.read(5.seconds)
            assertIs<ReadResult.Data>(result)
        }

    @Test
    fun read_returnsEnd_whenPeerCloses() =
        runTest {
            val (stream, server) = createStream()
            server.close()

            val result = stream.read(5.seconds)
            assertIs<ReadResult.End>(result)
        }

    @Test
    fun read_afterClose_throwsIllegalState() =
        runTest {
            val (stream, _) = createStream()
            stream.close()

            assertFailsWith<IllegalStateException> {
                stream.read(5.seconds)
            }
        }

    // --- Write path ---

    @Test
    fun write_sendsData_whenStreamOpen() =
        runTest {
            val (stream, server) = createStream()
            val buf = BufferFactory.Default.allocate(5)
            buf.writeBytes("hello".encodeToByteArray())
            buf.resetForRead()

            val written = stream.write(buf, 5.seconds)
            assertEquals(5, written.count)

            val result = server.read(5.seconds)
            assertIs<ReadResult.Data>(result)
        }

    @Test
    fun write_afterClose_throwsIllegalState() =
        runTest {
            val (stream, _) = createStream()
            stream.close()

            val buf = BufferFactory.Default.allocate(5)
            buf.writeBytes("hello".encodeToByteArray())
            buf.resetForRead()

            assertFailsWith<IllegalStateException> {
                stream.write(buf, 5.seconds)
            }
        }

    @Test
    fun writeGathered_afterClose_throwsIllegalState() =
        runTest {
            val (stream, _) = createStream()
            stream.close()

            val buf = BufferFactory.Default.allocate(1)
            buf.writeByte(0x42)
            buf.resetForRead()

            assertFailsWith<IllegalStateException> {
                stream.writeGathered(listOf(buf), 5.seconds)
            }
        }

    // --- Lifecycle ---

    @Test
    fun isOpen_trueWhenOpen() =
        runTest {
            val (stream, _) = createStream()
            assertTrue(stream.isOpen)
        }

    @Test
    fun isOpen_falseAfterClose() =
        runTest {
            val (stream, _) = createStream()
            stream.close()
            assertFalse(stream.isOpen)
        }

    @Test
    fun close_isIdempotent() =
        runTest {
            val (stream, _) = createStream()
            stream.close()
            stream.close() // should not throw
            assertFalse(stream.isOpen)
        }

    @Test
    fun streamId_isAccessible() {
        val (stream, _) = createStream()
        assertEquals(QuicStreamId(0), stream.streamId)
    }
}
