package com.ditchoom.socket

import com.ditchoom.buffer.FragmentedReadBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.data.Reader
import kotlin.time.Duration

/**
 * Non blocking, suspending socket input stream.
 */
class SuspendingSocketInputStream(
    private val readTimeout: Duration,
    private val reader: Reader,
    private val bufferSize: Int,
) {

    internal var currentBuffer: ReadBuffer? = null
        private set

    suspend fun readUnsignedByte() = sizedReadBuffer(UByte.SIZE_BYTES).readUnsignedByte()
    suspend fun readByte() = sizedReadBuffer(Byte.SIZE_BYTES).readByte()

    suspend fun sizedReadBuffer(size: Int): ReadBuffer {
        if (size < 1) {
            return EMPTY_BUFFER
        }
        val currentBuffer = currentBuffer
        var fragmentedLocalBuffer = if (currentBuffer != null && currentBuffer.hasRemaining()) {
            currentBuffer
        } else {
            val buffer = reader.read(readTimeout)
            buffer.resetForRead()
            buffer
        }
        this.currentBuffer = fragmentedLocalBuffer
        if (fragmentedLocalBuffer.remaining() >= size) {
            return fragmentedLocalBuffer
        }

        // ensure remaining in local buffer at least the size we requested
        while (fragmentedLocalBuffer.remaining() < size) {
            val moreData = reader.read(readTimeout)
            moreData.resetForRead()
            fragmentedLocalBuffer = FragmentedReadBuffer(fragmentedLocalBuffer, moreData)
        }
        this.currentBuffer = fragmentedLocalBuffer
        return fragmentedLocalBuffer
    }
}
