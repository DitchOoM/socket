@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.buffer.FragmentedReadBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.SuspendCloseable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime

/**
 * Non blocking, suspending socket input stream.
 */
@ExperimentalTime
class SuspendingSocketInputStream internal constructor(
    private val scope: CoroutineScope,
    private val socketFlowReader: SocketFlowReader,
): SuspendCloseable {

    private val incomingBufferChannel = Channel<ReadBuffer>(2)
    private var currentBuffer :ReadBuffer? = null

    suspend fun readUnsignedByte() = sizedReadBuffer(UByte.SIZE_BYTES).readUnsignedByte()
    suspend fun readByte() = sizedReadBuffer(Byte.SIZE_BYTES).readByte()

    suspend fun sizedReadBuffer(size: Int): ReadBuffer {
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
        this.currentBuffer = fragmentedLocalBuffer
        return fragmentedLocalBuffer
    }

    internal suspend fun startListeningToSocketAsync() {
        scope.launch {
            try {
                socketFlowReader.read().collect { readBuffer ->
                    val sliced = readBuffer.slice()
                    incomingBufferChannel.send(sliced)
                }
            } finally {
                close()
            }
        }
    }

    override suspend fun close() {
        incomingBufferChannel.close()
        socketFlowReader.close()
    }
}