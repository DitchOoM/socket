package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.data.readBuffer
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/**
 * Non blocking, suspending socket input stream.
 */
class SuspendingSocketInputStream(
    private val readTimeout: Duration,
    private val source: ByteSource,
) {
    internal var currentBuffer: ReadBuffer? = null

    suspend fun readUnsignedByte() = ensureBufferSize(UByte.SIZE_BYTES).readUnsignedByte()

    suspend fun readByte() = ensureBufferSize(Byte.SIZE_BYTES).readByte()

    suspend fun readBuffer(size: Int? = null): ReadBuffer {
        val buffer = ensureBufferSize(size)
        return if (size != null) {
            buffer.readBytes(size)
        } else {
            buffer
        }
    }

    internal suspend fun ensureBufferSize(size: Int? = null): ReadBuffer {
        if (size != null && size < 1) {
            return EMPTY_BUFFER
        }
        val currentBuffer = currentBuffer
        if (size == null) {
            val buffer =
                if (currentBuffer == null) {
                    val b = readFromReader().slice()
                    this.currentBuffer = b
                    b
                } else {
                    currentBuffer
                }
            return buffer
        }
        var fragmentedLocalBuffer =
            if (currentBuffer != null && currentBuffer.hasRemaining()) {
                currentBuffer
            } else {
                readFromReader()
            }
        this.currentBuffer = fragmentedLocalBuffer
        if (fragmentedLocalBuffer.remaining() >= size) {
            return fragmentedLocalBuffer
        }

        // ensure remaining in local buffer at least the size we requested.
        // buffer 5.3 removed the O(n²) FragmentedReadBuffer; accumulate into one
        // contiguous buffer via the zero-ByteArray write(ReadBuffer) primitive.
        while (fragmentedLocalBuffer.remaining() < size) {
            val moreData = readFromReader()
            val combined =
                BufferFactory.Default.allocate(
                    fragmentedLocalBuffer.remaining() + moreData.remaining(),
                    fragmentedLocalBuffer.byteOrder,
                )
            combined.write(fragmentedLocalBuffer)
            combined.write(moreData)
            combined.resetForRead()
            fragmentedLocalBuffer = combined.slice()
        }
        this.currentBuffer = fragmentedLocalBuffer
        return fragmentedLocalBuffer
    }

    private suspend fun readFromReader(): ReadBuffer {
        val bufferTimed =
            measureTimedValue {
                source.readBuffer(readTimeout)
            }
        val buffer = bufferTimed.value
        return buffer.slice()
    }
}
