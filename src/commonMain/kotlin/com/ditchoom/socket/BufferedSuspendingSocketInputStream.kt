@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.buffer.FragmentedReadBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.SuspendCloseable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            println("recv 1")
            incomingBufferChannel.receive()
        }

        println("recv b")
        while (fragmentedLocalBuffer.remaining() < size.toUInt()) {
            println("recv 2")
            val nextBuffer = incomingBufferChannel.receive()
            fragmentedLocalBuffer = FragmentedReadBuffer(fragmentedLocalBuffer, nextBuffer)
        }
        println("d")
        this.currentBuffer = fragmentedLocalBuffer
        return fragmentedLocalBuffer
    }

    internal suspend fun startListeningToSocketAsync() {
        scope.launch(Dispatchers.Default) {
            println("dispatched")
            try {
                socketFlowReader.read().collect { readBuffer ->
                    println("sending buffer")
                    incomingBufferChannel.send(readBuffer)
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