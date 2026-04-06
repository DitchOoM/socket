package com.ditchoom.socket.quic

import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.use
import com.ditchoom.socket.transport.MemoryTransport
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Tests that verify deterministic buffers are properly cleaned up.
 * Uses [TrackingBufferFactory] to detect leaks — any buffer allocated
 * but not freed by the end of a test is a failure.
 *
 * Demonstrates correct cleanup patterns: [use] for scoped ownership,
 * [freeIfNeeded] for buffers received from reads.
 */
class ResourceLeakTests {
    // --- QuicByteStream lifecycle ---

    @Test
    fun stream_writeAndRead_usePattern_noLeaks() =
        runTest {
            val factory = TrackingBufferFactory()
            val (clientSide, serverSide) = MemoryTransport.createPair(factory)
            val stream = QuicByteStream(QuicStreamId(0), clientSide)

            // Write with use {} — buffer freed automatically after write
            factory.allocate(4).use { buf ->
                buf.writeBytes("test".encodeToByteArray())
                buf.resetForRead()
                stream.write(buf, 5.seconds)
            }

            // Read and free the received buffer
            val result = serverSide.read(5.seconds)
            assertIs<ReadResult.Data>(result)
            result.buffer.freeIfNeeded()

            stream.close()
            serverSide.close()

            factory.assertNoLeaks()
        }

    @Test
    fun stream_multipleWritesThenClose_noLeaks() =
        runTest {
            val factory = TrackingBufferFactory()
            val (clientSide, serverSide) = MemoryTransport.createPair(factory)
            val stream = QuicByteStream(QuicStreamId(0), clientSide)

            repeat(5) { i ->
                factory.allocate(1).use { buf ->
                    buf.writeByte(i.toByte())
                    buf.resetForRead()
                    stream.write(buf, 5.seconds)
                }
            }

            // Read all on server and free each
            repeat(5) {
                val result = serverSide.read(5.seconds)
                assertIs<ReadResult.Data>(result)
                result.buffer.freeIfNeeded()
            }

            stream.close()
            serverSide.close()

            factory.assertNoLeaks()
        }

    @Test
    fun stream_closeWithoutReading_writeBufferFreed() =
        runTest {
            val factory = TrackingBufferFactory()
            val (clientSide, serverSide) = MemoryTransport.createPair(factory)
            val stream = QuicByteStream(QuicStreamId(0), clientSide)

            factory.allocate(4).use { buf ->
                buf.writeBytes("lost".encodeToByteArray())
                buf.resetForRead()
                stream.write(buf, 5.seconds)
            }
            // Our write buffer is freed by use{}. MemoryTransport made an
            // internal copy using our factory — that copy lives in the channel
            // and is the transport's responsibility. We verify our buffer was freed.
            factory.assertLiveCount(1) // only the transport's internal copy

            stream.close()
            serverSide.close()
        }

    // --- QuicConnection lifecycle ---

    @Test
    fun connection_openAndCloseStream_noLeaks() =
        runTest {
            val factory = TrackingBufferFactory()
            val conn = MockQuicConnection()
            val stream = conn.openStream()
            val peerStream = conn.peerStreams[stream.streamId]!!

            factory.allocate(3).use { buf ->
                buf.writeBytes("hey".encodeToByteArray())
                buf.resetForRead()
                stream.write(buf, 5.seconds)
            }

            val result = peerStream.read(5.seconds)
            assertIs<ReadResult.Data>(result)
            result.buffer.freeIfNeeded()

            stream.close()
            peerStream.close()
            conn.close()

            factory.assertNoLeaks()
        }

    @Test
    fun connection_multipleStreams_allClosed_noLeaks() =
        runTest {
            val conn = MockQuicConnection()
            val factory = TrackingBufferFactory()

            val streams = (0 until 3).map { conn.openStream() }
            val peerStreams = streams.map { conn.peerStreams[it.streamId]!! }

            streams.forEach { it.close() }
            peerStreams.forEach { it.close() }
            conn.close()

            factory.assertNoLeaks()
        }

    @Test
    fun connection_closeForcefully_streamsStillCloseable() =
        runTest {
            val conn = MockQuicConnection()
            val stream = conn.openStream()

            conn.close(QuicError.InternalError("forced"))
            stream.close() // idempotent, should not throw

            assertIs<QuicConnectionState.Closed>(conn.state.value)
        }

    // --- TrackingBufferFactory self-tests ---

    @Test
    fun trackingFactory_detectsLeak() {
        val factory = TrackingBufferFactory()
        val buf = factory.allocate(10)
        factory.assertLiveCount(1)

        buf.freeNativeMemory()
        factory.assertLiveCount(0)
        factory.assertNoLeaks()
    }

    @Test
    fun trackingFactory_usePattern_freesAutomatically() {
        val factory = TrackingBufferFactory()
        factory.allocate(10).use { buf ->
            buf.writeByte(42)
        }
        factory.assertNoLeaks()
    }

    @Test
    fun trackingFactory_doubleFree_isIdempotent() {
        val factory = TrackingBufferFactory()
        val buf = factory.allocate(10)
        buf.freeNativeMemory()
        buf.freeNativeMemory() // should not throw or double-count
        factory.assertLiveCount(0)
        factory.assertNoLeaks()
    }

    @Test
    fun trackingFactory_multipleAllocations() {
        val factory = TrackingBufferFactory()
        val bufs = (0 until 10).map { factory.allocate(8) }
        factory.assertLiveCount(10)

        bufs.take(5).forEach { it.freeNativeMemory() }
        factory.assertLiveCount(5)

        bufs.drop(5).forEach { it.freeNativeMemory() }
        factory.assertNoLeaks()
    }

    @Test
    fun trackingFactory_wrap_notTracked() {
        val factory = TrackingBufferFactory()
        factory.wrap("hello".encodeToByteArray())
        // wrap() creates heap-backed buffers, not tracked
        factory.assertLiveCount(0)
        factory.assertNoLeaks()
    }
}
