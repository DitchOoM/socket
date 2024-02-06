package com.ditchoom.socket

import com.ditchoom.buffer.FragmentedReadBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.data.Reader
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/**
 * Non blocking, suspending socket input stream.
 */
class SuspendingSocketInputStream(
    private val readTimeout: Duration,
    private val reader: Reader
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
            val buffer = if (currentBuffer == null) {
                val b = readFromReader().slice()
                this.currentBuffer = b
                b
            } else {
                currentBuffer
            }
            return buffer
        }
        var fragmentedLocalBuffer = if (currentBuffer != null && currentBuffer.hasRemaining()) {
            currentBuffer
        } else {
            readFromReader()
        }
        this.currentBuffer = fragmentedLocalBuffer
        if (fragmentedLocalBuffer.remaining() >= size) {
            return fragmentedLocalBuffer
        }

        // ensure remaining in local buffer at least the size we requested
        while (fragmentedLocalBuffer.remaining() < size) {
            val moreData = readFromReader()
            fragmentedLocalBuffer = FragmentedReadBuffer(fragmentedLocalBuffer, moreData).slice()
        }
        this.currentBuffer = fragmentedLocalBuffer
        return fragmentedLocalBuffer
    }

    private suspend fun readFromReader(): ReadBuffer {
        val bufferTimed = measureTimedValue {
            reader.read(readTimeout)
        }
        val buffer = bufferTimed.value
        buffer.resetForRead()
        return buffer.slice()
    }
}
