package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A minimal [ByteSource] that yields one byte per [read], starting from [start] and incrementing.
 * Replaces the old single-byte `Reader` fakes after the v6 byte-stream migration.
 */
private class IncrementingByteSource(
    start: Byte = 0,
) : ByteSource {
    private var internalCount = start

    override val isOpen: Boolean = true

    override val readPolicy: ReadPolicy = ReadPolicy.Bounded(100.seconds)

    override suspend fun read(deadline: Duration): ReadResult {
        val buffer = BufferFactory.Default.allocate(1)
        buffer.writeByte(internalCount++)
        buffer.resetForRead()
        return ReadResult.Data(buffer)
    }
}

class SuspendingSocketInputStreamTests {
    @Test
    fun ensureBufferSize_doNotReadAhead_NoCurrentBuffer_NotFragmented() =
        runTest {
            // Single byte 0 per read.
            val source = IncrementingByteSource(start = 0)
            val inputStream = SuspendingSocketInputStream(100.seconds, source)
            assertEquals(EMPTY_BUFFER, inputStream.ensureBufferSize(0))
            assertNull(inputStream.currentBuffer)
            val oneByteBuffer = inputStream.ensureBufferSize(1)
            assertEquals(0, oneByteBuffer.position())
            assertEquals(1, oneByteBuffer.limit())
            assertEquals(0, oneByteBuffer.readByte())
        }

    @Test
    fun ensureBufferSize_doNotReadAhead_NoCurrentBuffer_Fragmented() =
        runTest {
            val source = IncrementingByteSource(start = 0)
            val inputStream = SuspendingSocketInputStream(100.seconds, source)
            assertEquals(EMPTY_BUFFER, inputStream.ensureBufferSize(0))
            assertNull(inputStream.currentBuffer)
            val twoByteBuffer = inputStream.ensureBufferSize(2)
            assertEquals(0, twoByteBuffer.position())
            assertEquals(2, twoByteBuffer.limit())
            assertEquals(0, twoByteBuffer.readByte())
            assertEquals(1, twoByteBuffer.readByte())
        }

    @Test
    fun ensureBufferSize_doNotReadAhead_HasCurrentBuffer_Fragmented() =
        runTest {
            val source = IncrementingByteSource(start = 1)
            val inputStream = SuspendingSocketInputStream(100.seconds, source)
            inputStream.currentBuffer = BufferFactory.Default.wrap(byteArrayOf(0))
            assertEquals(EMPTY_BUFFER, inputStream.ensureBufferSize(0))
            val twoByteBuffer = inputStream.ensureBufferSize(2)
            assertEquals(0, twoByteBuffer.position())
            assertEquals(2, twoByteBuffer.limit())
            assertEquals(0, twoByteBuffer.readByte())
            assertEquals(1, twoByteBuffer.readByte())
        }

    @Test
    fun ensureBufferSize_shouldReadAhead_HasDeferredBuffer_HasCurrentBuffer_Fragmented() =
        runTest {
            val source = IncrementingByteSource(start = 1)
            val inputStream = SuspendingSocketInputStream(100.seconds, source)
            inputStream.currentBuffer = BufferFactory.Default.wrap(byteArrayOf(0))
            assertEquals(EMPTY_BUFFER, inputStream.ensureBufferSize(0))
            assertEquals(0, inputStream.readByte())
            assertEquals(1, inputStream.readByte())
            assertEquals(2, inputStream.readByte())
            assertEquals(3, inputStream.readByte())
        }

    @Test
    fun ensureBufferSize_nullSize_NoCurrentBuffer() =
        runTest {
            val source = IncrementingByteSource(start = 1)
            val inputStream = SuspendingSocketInputStream(100.seconds, source)
            inputStream.currentBuffer = BufferFactory.Default.wrap(byteArrayOf(0))
            assertEquals(EMPTY_BUFFER, inputStream.ensureBufferSize(0))
            assertEquals(0, inputStream.readByte())
            assertEquals(1, inputStream.readByte())
            assertEquals(2, inputStream.readByte())
            assertEquals(3, inputStream.readByte())
        }
}
