package com.ditchoom.socket

import com.ditchoom.buffer.FragmentedReadBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.data.Reader
import kotlin.time.Duration

/**
 * Non blocking, suspending socket input stream.
 */
class SuspendingSocketInputStream(
    private val readTimeout: Duration,
    private val reader: Reader<ReadBuffer>,
) {

    internal var currentBuffer: ReadBuffer? = null
        private set

    suspend fun readUnsignedByte() = sizedReadBuffer(UByte.SIZE_BYTES).readUnsignedByte()
    suspend fun readByte() = sizedReadBuffer(Byte.SIZE_BYTES).readByte()

    suspend fun sizedReadBuffer(size: Int): ReadBuffer {
        if (size < 1) {
            return emptyBuffer
        }
        val currentBuffer = currentBuffer
        var fragmentedLocalBuffer = if (currentBuffer != null && currentBuffer.hasRemaining()) {
            currentBuffer
        } else {
            val dataRead = reader.readData(readTimeout)
            dataRead
        }
        this.currentBuffer = fragmentedLocalBuffer
        if (fragmentedLocalBuffer.remaining().toInt() >= size) {
            return fragmentedLocalBuffer
        }

        // ensure remaining in local buffer at least the size we requested
        while (fragmentedLocalBuffer.remaining() < size) {
            val moreData = reader.readData(readTimeout)
            fragmentedLocalBuffer = FragmentedReadBuffer(fragmentedLocalBuffer, moreData)
        }
        this.currentBuffer = fragmentedLocalBuffer
        return fragmentedLocalBuffer
    }

    companion object {
        private val emptyBuffer = PlatformBuffer.allocate(0)
    }
}