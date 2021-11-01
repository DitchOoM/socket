@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.buffer.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime

/**
 * Non blocking, suspending socket input stream.
 */
@ExperimentalTime
class SuspendingSocketInputStream internal constructor(
    private val scope: CoroutineScope,
    private val bufferedReader: BufferedReader,
): SuspendCloseable {

    var transformer: ((UInt, Byte) -> Byte)? = null

    private val incomingBufferChannel = Channel<ReadBuffer>(2)
    private var currentBuffer :ReadBuffer? = null
    private val readerJob = scope.launch {
        try {
            bufferedReader.read().collect { readBuffer ->
                val sliced = readBuffer.slice()
                incomingBufferChannel.send(sliced)
            }
        } finally {
            close()
        }
    }
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
            incomingBufferChannel.receive()
        }
        this.currentBuffer = fragmentedLocalBuffer
        if (fragmentedLocalBuffer.remaining().toInt() >= size) {
            return fragmentedLocalBuffer
        }

        // ensure remaining in local buffer at least the size we requested
        while (fragmentedLocalBuffer.remaining() < size.toUInt()) {
            fragmentedLocalBuffer = FragmentedReadBuffer(fragmentedLocalBuffer, incomingBufferChannel.receive())
        }
        val transformer = transformer
        val buffer = if (transformer != null) {
            TransformedReadBuffer(fragmentedLocalBuffer, transformer)
        } else {
            fragmentedLocalBuffer
        }
        this.currentBuffer = buffer
        return buffer
    }

    override suspend fun close() {
        incomingBufferChannel.cancel()
        incomingBufferChannel.close()
        bufferedReader.close()
    }
}