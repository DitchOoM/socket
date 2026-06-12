package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.StreamProcessor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration

class Http3StreamReaderTests {
    private fun pool() =
        BufferPool(
            threadingMode = ThreadingMode.SingleThreaded,
            maxPoolSize = 8,
            defaultBufferSize = 256,
            factory = BufferFactory.Default,
        )

    private fun reader(stream: ByteStream) = Http3StreamReader(stream, StreamProcessor.create(pool(), ByteOrder.BIG_ENDIAN))

    /** Serializes [frame] to its RFC 9114 §7.1 bytes. */
    private fun frameBytes(frame: Http3Frame): List<Int> {
        val buf = BufferFactory.Default.allocate(256)
        HandwrittenHttp3FrameCodec.encode(buf, frame, EncodeContext.Empty)
        buf.resetForRead()
        return (0 until buf.remaining()).map { buf.readByte().toInt() and 0xFF }
    }

    private fun dataChunk(bytes: List<Int>): ReadResult {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return ReadResult.Data(buf)
    }

    private fun ReadBuffer.toIntList(): List<Int> = (0 until remaining()).map { get(position() + it).toInt() and 0xFF }

    private fun settings() =
        Http3Frame.Settings(
            listOf(
                Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 0L),
                Http3Setting(Http3SettingId.QPACK_BLOCKED_STREAMS, 0L),
            ),
        )

    private fun dataFrame(vararg payload: Int): Http3Frame.Data {
        val buf = BufferFactory.Default.allocate(payload.size.coerceAtLeast(1))
        for (b in payload) buf.writeByte(b.toByte())
        buf.resetForRead()
        return Http3Frame.Data(buf)
    }

    /** A [ByteStream] that hands out a fixed script of [ReadResult]s, then [ReadResult.End]. */
    private class ScriptedByteStream(
        results: List<ReadResult>,
    ) : ByteStream {
        private val queue = ArrayDeque(results)
        override val isOpen: Boolean get() = queue.isNotEmpty()

        override suspend fun read(timeout: Duration): ReadResult = if (queue.isEmpty()) ReadResult.End else queue.removeFirst()

        override suspend fun write(
            buffer: ReadBuffer,
            timeout: Duration,
        ): BytesWritten = throw UnsupportedOperationException("read-only test stream")

        override suspend fun close() = Unit
    }

    // --- happy path ---------------------------------------------------------

    @Test
    fun nextFrame_returnsSettings_thenNullAtEnd() =
        runTest {
            val r = reader(ScriptedByteStream(listOf(dataChunk(frameBytes(settings())), ReadResult.End)))
            assertEquals(settings(), r.nextFrame())
            assertNull(r.nextFrame())
        }

    @Test
    fun nextFrame_readsMultipleFramesInOneChunk() =
        runTest {
            val bytes = frameBytes(settings()) + frameBytes(dataFrame(0x41, 0x42, 0x43))
            val r = reader(ScriptedByteStream(listOf(dataChunk(bytes))))
            assertEquals(settings(), r.nextFrame())
            val data = r.nextFrame()
            assertIs<Http3Frame.Data>(data)
            assertEquals(listOf(0x41, 0x42, 0x43), data.payload.toIntList())
            assertNull(r.nextFrame())
        }

    @Test
    fun nextFrame_reassemblesFrameSplitAcrossReads() =
        runTest {
            // Settings frame delivered one byte at a time — header and payload straddle reads.
            val bytes = frameBytes(settings())
            val r = reader(ScriptedByteStream(bytes.map { dataChunk(listOf(it)) } + ReadResult.End))
            assertEquals(settings(), r.nextFrame())
            assertNull(r.nextFrame())
        }

    @Test
    fun nextFrame_headerAndPayloadInSeparateReads() =
        runTest {
            // Split exactly between the 2-byte header (type+len) and the 3-byte payload.
            val bytes = frameBytes(dataFrame(0x78, 0x79, 0x7A))
            val r = reader(ScriptedByteStream(listOf(dataChunk(bytes.take(2)), dataChunk(bytes.drop(2)), ReadResult.End)))
            val data = r.nextFrame()
            assertIs<Http3Frame.Data>(data)
            assertEquals(3, data.payload.remaining())
            assertNull(r.nextFrame())
        }

    // --- error paths --------------------------------------------------------

    @Test
    fun nextFrame_truncatedHeaderAtEnd_throws() =
        runTest {
            // A lone type byte (no length varint) then FIN.
            val r = reader(ScriptedByteStream(listOf(dataChunk(listOf(0x04)), ReadResult.End)))
            assertFailsWith<Http3StreamException> { r.nextFrame() }
        }

    @Test
    fun nextFrame_truncatedPayloadAtEnd_throws() =
        runTest {
            // SETTINGS header promises N payload bytes; deliver the header plus one short byte.
            val bytes = frameBytes(settings())
            val r = reader(ScriptedByteStream(listOf(dataChunk(bytes.dropLast(1)), ReadResult.End)))
            assertFailsWith<Http3StreamException> { r.nextFrame() }
        }

    @Test
    fun nextFrame_reset_throws() =
        runTest {
            val r = reader(ScriptedByteStream(listOf(dataChunk(frameBytes(settings()).take(1)), ReadResult.Reset)))
            assertFailsWith<Http3StreamException> { r.nextFrame() }
        }

    @Test
    fun nextFrame_drainsBufferedFramesAfterEnd_beforeReportingTruncation() =
        runTest {
            // One whole frame followed by a truncated one in the same buffer, then FIN: the whole
            // frame must come out first, and only the leftover partial frame is the error.
            val bytes = frameBytes(settings()) + frameBytes(settings()).dropLast(1)
            val r = reader(ScriptedByteStream(listOf(dataChunk(bytes), ReadResult.End)))
            assertEquals(settings(), r.nextFrame())
            assertFailsWith<Http3StreamException> { r.nextFrame() }
        }

    @Test
    fun nextFrame_emptyStream_returnsNull() =
        runTest {
            assertNull(reader(ScriptedByteStream(listOf(ReadResult.End))).nextFrame())
        }

    // --- nextVarInt (uni stream-type prefix) --------------------------------

    @Test
    fun nextVarInt_thenFrames_onOneReader() =
        runTest {
            // A control stream: type prefix 0x00, then a SETTINGS frame — both off one reader.
            val bytes = listOf(Http3StreamType.CONTROL.toInt()) + frameBytes(settings())
            val r = reader(ScriptedByteStream(listOf(dataChunk(bytes), ReadResult.End)))
            assertEquals(Http3StreamType.CONTROL, r.nextVarInt())
            assertEquals(settings(), r.nextFrame())
            assertNull(r.nextFrame())
        }

    @Test
    fun nextVarInt_reassemblesMultiByteVarintAcrossReads() =
        runTest {
            // 0x4040 = two-byte varint for 64; delivered one byte at a time.
            val r = reader(ScriptedByteStream(listOf(dataChunk(listOf(0x40)), dataChunk(listOf(0x40)), ReadResult.End)))
            assertEquals(64L, r.nextVarInt())
        }

    @Test
    fun nextVarInt_streamEndsBeforeComplete_throws() =
        runTest {
            // First byte of a two-byte varint then FIN.
            val r = reader(ScriptedByteStream(listOf(dataChunk(listOf(0x40)), ReadResult.End)))
            assertFailsWith<Http3StreamException> { r.nextVarInt() }
        }

    // --- buffer recycling ----------------------------------------------------
    // The reader releases the previous frame's wire buffer (and its borrowed payload view)
    // at the start of the next nextFrame() call — the documented end of the borrow window —
    // so pooled chunks actually return to the pool instead of leaking until GC.

    /** Writes [bytes] into a buffer acquired from [pool], ready to read. */
    private fun pooledChunk(
        pool: BufferPool,
        bytes: List<Int>,
    ): ReadResult {
        val buf = pool.acquire(bytes.size)
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return ReadResult.Data(buf)
    }

    @Test
    fun recycling_exactFitPooledChunk_returnsToPoolOnNextCall() =
        runTest {
            val pool = pool()
            // One chunk carrying exactly one DATA frame: the processor hands the chunk itself
            // to the reader (zero-copy transfer), so only the reader can return it to the pool.
            val chunk = pooledChunk(pool, frameBytes(dataFrame(0x01, 0x02)))
            val r = Http3StreamReader(ScriptedByteStream(listOf(chunk, ReadResult.End)), StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN))

            val data = r.nextFrame()
            assertIs<Http3Frame.Data>(data)
            // The borrow window is open: the payload view must be readable now…
            assertEquals(listOf(0x01, 0x02), data.payload.toIntList())
            assertEquals(0, pool.stats().currentPoolSize)

            // …and the next call closes it, releasing chunk + payload refs back to the pool.
            assertNull(r.nextFrame())
            assertEquals(1, pool.stats().currentPoolSize)
        }

    @Test
    fun recycling_mergedCopyFromSplitFrame_returnsToPoolOnNextCall() =
        runTest {
            val pool = pool()
            // The frame straddles two chunks, so the processor merges into a pool-acquired copy.
            val bytes = frameBytes(dataFrame(0x61, 0x62, 0x63))
            val stream = ScriptedByteStream(listOf(dataChunk(bytes.take(3)), dataChunk(bytes.drop(3)), ReadResult.End))
            val r = Http3StreamReader(stream, StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN))

            val data = r.nextFrame()
            assertIs<Http3Frame.Data>(data)
            assertEquals(listOf(0x61, 0x62, 0x63), data.payload.toIntList())

            assertNull(r.nextFrame())
            assertEquals(1, pool.stats().currentPoolSize)
        }

    @Test
    fun recycling_valueFrame_recyclesWireBytesImmediately() =
        runTest {
            val pool = pool()
            // SETTINGS decodes into owned values — nothing borrows the wire bytes, so the
            // exact-fit chunk goes back to the pool before the next call.
            val chunk = pooledChunk(pool, frameBytes(settings()))
            val r = Http3StreamReader(ScriptedByteStream(listOf(chunk, ReadResult.End)), StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN))

            assertEquals(settings(), r.nextFrame())
            assertEquals(1, pool.stats().currentPoolSize)
        }

    @Test
    fun recycling_release_recyclesRetainedFrame() =
        runTest {
            val pool = pool()
            val chunk = pooledChunk(pool, frameBytes(dataFrame(0x7F)))
            val r = Http3StreamReader(ScriptedByteStream(listOf(chunk, ReadResult.End)), StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN))

            assertIs<Http3Frame.Data>(r.nextFrame())
            assertEquals(0, pool.stats().currentPoolSize)
            // No further nextFrame: release() must close the borrow window itself.
            r.release()
            assertEquals(1, pool.stats().currentPoolSize)
        }

    @Test
    fun recycling_emptyPayload_doesNotFreeSharedEmptyBuffer() =
        runTest {
            // Two zero-length DATA frames: both payloads are the shared EMPTY_BUFFER singleton,
            // which must survive the first frame's recycle untouched.
            val bytes = frameBytes(dataFrame()) + frameBytes(dataFrame())
            val r = reader(ScriptedByteStream(listOf(dataChunk(bytes), ReadResult.End)))

            val first = r.nextFrame()
            assertIs<Http3Frame.Data>(first)
            assertEquals(0, first.payload.remaining())
            val second = r.nextFrame()
            assertIs<Http3Frame.Data>(second)
            assertEquals(0, second.payload.remaining())
            assertNull(r.nextFrame())
        }
}
