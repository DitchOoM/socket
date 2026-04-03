package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.ConnectionOptions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

class MemoryTransport : Transport {
    override suspend fun connect(
        hostname: String,
        port: Int,
        options: ConnectionOptions,
    ): ByteStream = createPair(options.bufferFactory).first

    companion object {
        fun createPair(bufferFactory: BufferFactory = BufferFactory.Default): Pair<ByteStream, ByteStream> {
            val aToB = Channel<ReadBuffer>(Channel.UNLIMITED)
            val bToA = Channel<ReadBuffer>(Channel.UNLIMITED)
            val a = MemoryByteStream(readChannel = bToA, writeChannel = aToB, bufferFactory = bufferFactory)
            val b = MemoryByteStream(readChannel = aToB, writeChannel = bToA, bufferFactory = bufferFactory)
            return a to b
        }
    }
}

class MemoryByteStream(
    private val readChannel: Channel<ReadBuffer>,
    private val writeChannel: Channel<ReadBuffer>,
    private val bufferFactory: BufferFactory = BufferFactory.Default,
) : ByteStream {
    override val isOpen: Boolean get() = !readChannel.isClosedForReceive

    override suspend fun read(timeout: Duration): ReadResult =
        try {
            val buffer = withTimeout(timeout) { readChannel.receive() }
            ReadResult.Data(buffer)
        } catch (_: ClosedReceiveChannelException) {
            ReadResult.End
        }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): BytesWritten {
        val remaining = buffer.remaining()
        val copy = bufferFactory.allocate(remaining)
        copy.write(buffer)
        copy.resetForRead()
        writeChannel.send(copy)
        return BytesWritten(remaining)
    }

    override suspend fun close() {
        readChannel.cancel()
        writeChannel.close()
    }
}
