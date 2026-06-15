package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

class MemoryTransport : Transport {
    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream = createPair(config).first

    companion object {
        fun createPair(config: TransportConfig = TransportConfig()): Pair<ByteStream, ByteStream> {
            val aToB = Channel<ReadBuffer>(Channel.UNLIMITED)
            val bToA = Channel<ReadBuffer>(Channel.UNLIMITED)
            val a = MemoryByteStream(readChannel = bToA, writeChannel = aToB, config = config)
            val b = MemoryByteStream(readChannel = aToB, writeChannel = bToA, config = config)
            return a to b
        }
    }
}

class MemoryByteStream(
    private val readChannel: Channel<ReadBuffer>,
    private val writeChannel: Channel<ReadBuffer>,
    private val config: TransportConfig = TransportConfig(),
) : ByteStream {
    private val bufferFactory: BufferFactory = config.bufferFactory
    override val readPolicy: ReadPolicy = config.readPolicy
    override val writePolicy: WritePolicy = config.writePolicy

    override val isOpen: Boolean get() = !readChannel.isClosedForReceive

    override suspend fun read(deadline: Duration): ReadResult =
        try {
            val buffer =
                if (deadline.isFinite()) {
                    withTimeout(deadline) { readChannel.receive() }
                } else {
                    readChannel.receive()
                }
            ReadResult.Data(buffer)
        } catch (_: ClosedReceiveChannelException) {
            ReadResult.End
        } catch (e: CancellationException) {
            if (readChannel.isClosedForReceive) {
                ReadResult.End
            } else {
                throw e
            }
        }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        val remaining = buffer.remaining()
        val copy = bufferFactory.allocate(remaining)
        copy.write(buffer)
        copy.resetForRead()
        try {
            writeChannel.send(copy)
        } catch (_: ClosedSendChannelException) {
            throw SocketClosedException.General("Stream is closed")
        }
        return BytesWritten(remaining)
    }

    override suspend fun close() {
        readChannel.cancel()
        writeChannel.close()
    }
}
