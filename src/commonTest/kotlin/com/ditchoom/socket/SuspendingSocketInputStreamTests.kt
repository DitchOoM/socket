package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.wrap
import com.ditchoom.data.Reader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SuspendingSocketInputStreamTests {
    @Test
    fun ensureBufferSize_doNotReadAhead_NoCurrentBuffer_NotFragmented() = runTest {
        val reader =
            object : Reader {
                override fun isOpen(): Boolean = true

                override suspend fun read(timeout: Duration): ReadBuffer {
                    val buffer = PlatformBuffer.allocate(1)
                    buffer.writeByte(0.toByte())
                    return buffer
                }
            }
        val inputStream = SuspendingSocketInputStream(100.seconds, reader)
        assertEquals(EMPTY_BUFFER, inputStream.ensureBufferSize(0))
        assertNull(inputStream.currentBuffer)
        val oneByteBuffer = inputStream.ensureBufferSize(1)
        assertEquals(0, oneByteBuffer.position())
        assertEquals(1, oneByteBuffer.limit())
        assertEquals(0, oneByteBuffer.readByte())
    }

    @Test
    fun ensureBufferSize_doNotReadAhead_NoCurrentBuffer_Fragmented() = runTest {
        val reader =
            object : Reader {
                private var internalCount = 0.toByte()

                override fun isOpen(): Boolean = true

                override suspend fun read(timeout: Duration): ReadBuffer {
                    val buffer = PlatformBuffer.allocate(1)
                    buffer.writeByte(internalCount++)
                    return buffer
                }
            }
        val inputStream = SuspendingSocketInputStream(100.seconds, reader)
        assertEquals(EMPTY_BUFFER, inputStream.ensureBufferSize(0))
        assertNull(inputStream.currentBuffer)
        val twoByteBuffer = inputStream.ensureBufferSize(2)
        assertEquals(0, twoByteBuffer.position())
        assertEquals(2, twoByteBuffer.limit())
        assertEquals(0, twoByteBuffer.readByte())
        assertEquals(1, twoByteBuffer.readByte())
    }

    @Test
    fun ensureBufferSize_doNotReadAhead_HasCurrentBuffer_Fragmented() = runTest {
        val reader =
            object : Reader {
                private var internalCount = 1.toByte()

                override fun isOpen(): Boolean = true

                override suspend fun read(timeout: Duration): ReadBuffer {
                    val buffer = PlatformBuffer.allocate(1)
                    buffer.writeByte(internalCount++)
                    return buffer
                }
            }
        val inputStream = SuspendingSocketInputStream(100.seconds, reader)
        inputStream.currentBuffer = PlatformBuffer.wrap(byteArrayOf(0))
        assertEquals(EMPTY_BUFFER, inputStream.ensureBufferSize(0))
        val twoByteBuffer = inputStream.ensureBufferSize(2)
        assertEquals(0, twoByteBuffer.position())
        assertEquals(2, twoByteBuffer.limit())
        assertEquals(0, twoByteBuffer.readByte())
        assertEquals(1, twoByteBuffer.readByte())
    }

    @Test
    fun ensureBufferSize_shouldReadAhead_HasDeferredBuffer_HasCurrentBuffer_Fragmented() = runTest {
        val reader =
            object : Reader {
                private var internalCount = 1.toByte()

                override fun isOpen(): Boolean = true

                override suspend fun read(timeout: Duration): ReadBuffer {
                    val buffer = PlatformBuffer.allocate(1)
                    buffer.writeByte(internalCount++)
                    return buffer
                }
            }
        val inputStream = SuspendingSocketInputStream(100.seconds, reader)
        inputStream.currentBuffer = PlatformBuffer.wrap(byteArrayOf(0))
        assertEquals(EMPTY_BUFFER, inputStream.ensureBufferSize(0))
        assertEquals(0, inputStream.readByte())
        assertEquals(1, inputStream.readByte())
        assertEquals(2, inputStream.readByte())
        assertEquals(3, inputStream.readByte())
    }

    @Test
    fun ensureBufferSize_nullSize_NoCurrentBuffer() = runTest {
        val reader =
            object : Reader {
                private var internalCount = 1.toByte()

                override fun isOpen(): Boolean = true

                override suspend fun read(timeout: Duration): ReadBuffer {
                    val buffer = PlatformBuffer.allocate(1)
                    buffer.writeByte(internalCount++)
                    return buffer
                }
            }
        val inputStream = SuspendingSocketInputStream(100.seconds, reader)
        inputStream.currentBuffer = PlatformBuffer.wrap(byteArrayOf(0))
        assertEquals(EMPTY_BUFFER, inputStream.ensureBufferSize(0))
        assertEquals(0, inputStream.readByte())
        assertEquals(1, inputStream.readByte())
        assertEquals(2, inputStream.readByte())
        assertEquals(3, inputStream.readByte())
    }
}
