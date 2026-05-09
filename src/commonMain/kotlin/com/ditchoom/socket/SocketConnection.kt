package com.ditchoom.socket

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
 * Buffer allocation is controlled by the caller via [ConnectionOptions.bufferPool].
 * The pool feeds both the stream processor's chunk staging and per-call allocations
 * (via `BufferPool : BufferFactory`).
 */
class SocketConnection private constructor(
    val socket: ClientToServerSocket,
    val options: ConnectionOptions,
    val stream: SuspendingStreamProcessor,
) {
    val bufferPool: BufferPool get() = options.bufferPool
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
     * Acquires a buffer from [bufferPool], runs [block] with it, and returns it on exit.
     * Pooled buffers' `freeIfNeeded` returns the buffer to the pool.
     */
    inline fun <T> withBuffer(
        minSize: Int = 0,
        block: (PlatformBuffer) -> T,
    ): T {
        val buf = bufferPool.allocate(minSize) as PlatformBuffer
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
            socket.bufferFactory = options.bufferPool
            socket.open(port, options.connectionTimeout, hostname, options.socketOptions)
            val stream = StreamProcessor.builder(options.bufferPool).buildSuspending()
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
            socket.bufferFactory = options.bufferPool
            val stream = StreamProcessor.builder(options.bufferPool).buildSuspending()
            return SocketConnection(socket, options, stream)
        }
    }
}
