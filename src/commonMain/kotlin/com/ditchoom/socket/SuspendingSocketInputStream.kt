@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.buffer.FragmentedReadBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocateNewBuffer
import com.ditchoom.data.Reader
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * Non blocking, suspending socket input stream.
 */
@ExperimentalTime
class SuspendingSocketInputStream(
    private val readTimeout: Duration,
    private val reader: Reader<ReadBuffer>,
) {

    internal var currentBuffer: ReadBuffer? = null
        private set

    private val emptyBuffer = allocateNewBuffer(0u)

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
        while (fragmentedLocalBuffer.remaining() < size.toUInt()) {
            val moreData = reader.readData(readTimeout)
            //println("ReadM $moreData ${byteArray(moreData)}")
            fragmentedLocalBuffer = FragmentedReadBuffer(fragmentedLocalBuffer,moreData)
        }
        this.currentBuffer = fragmentedLocalBuffer
        return fragmentedLocalBuffer
    }

}