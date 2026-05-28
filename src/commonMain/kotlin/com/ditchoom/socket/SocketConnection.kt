package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.SuspendingStreamProcessor
import com.ditchoom.buffer.stream.builder
import kotlin.time.Duration

/**
 * A composed socket connection bundling a socket + stream processor.
 *
 * Buffer allocation is controlled by the caller via [ConnectionOptions.bufferFactory].
 * An internal pool is constructed from that factory to back the stream processor and
 * per-call helpers; the pool is never exposed.
 */
class SocketConnection private constructor(
    val socket: ClientToServerSocket,
    val options: ConnectionOptions,
    val stream: SuspendingStreamProcessor,
) {
    val bufferFactory: BufferFactory get() = options.bufferFactory
    val isOpen: Boolean get() = socket.isOpen()

    /**
     * Reads data from socket into the stream processor.
     *
     * Delegates to socket.read(timeout) which returns a platform-native buffer
     * (zero-copy on JS/Apple, SO_RCVBUF-sized on JVM/Linux). The buffer is
     * transferred to the stream processor or freed if empty.
     */
    suspend fun readIntoStream(timeout: Duration = options.readTimeout): Int {
        val buffer = socket.read(timeout)
        val bytesRead = buffer.remaining()
        if (bytesRead > 0) {
            stream.append(buffer)
        } else {
            buffer.freeIfNeeded()
        }
        return bytesRead
    }

    suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration = options.writeTimeout,
    ): Int = socket.write(buffer, timeout)

    suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        timeout: Duration = options.writeTimeout,
    ): Int = socket.writeGathered(buffers, timeout)

    /**
     * Allocates a buffer from [bufferFactory], runs [block] with it, and frees it on exit.
     */
    inline fun <T> withBuffer(
        minSize: Int = 0,
        block: (PlatformBuffer) -> T,
    ): T {
        val buf = bufferFactory.allocate(minSize)
        return try {
            block(buf)
        } finally {
            buf.freeIfNeeded()
        }
    }

    suspend fun close() {
        socket.close()
    }

    companion object {
        suspend fun connect(
            hostname: String,
            port: Int,
            options: ConnectionOptions = ConnectionOptions(),
        ): SocketConnection {
            val socket = ClientSocket.allocate()
            socket.bufferFactory = options.bufferFactory
            socket.open(port, options.connectionTimeout, hostname, options.socketOptions)
            val stream = StreamProcessor.builder(BufferPool(factory = options.bufferFactory)).buildSuspending()
            return SocketConnection(socket, options, stream)
        }

        suspend fun <T> connect(
            hostname: String,
            port: Int,
            options: ConnectionOptions = ConnectionOptions(),
            block: suspend (SocketConnection) -> T,
        ): T {
            val connection = connect(hostname, port, options)
            return try {
                block(connection)
            } finally {
                connection.close()
            }
        }

        fun wrap(
            socket: ClientToServerSocket,
            options: ConnectionOptions = ConnectionOptions(),
        ): SocketConnection {
            socket.bufferFactory = options.bufferFactory
            val stream = StreamProcessor.builder(BufferPool(factory = options.bufferFactory)).buildSuspending()
            return SocketConnection(socket, options, stream)
        }
    }
}
