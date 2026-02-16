package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.SuspendingStreamProcessor
import com.ditchoom.buffer.stream.builder
import kotlin.time.Duration

/**
 * A composed socket connection that bundles a socket + buffer pool + stream processor.
 *
 * Protocol implementations (WebSocket, MQTT, HTTP) build on this instead of managing
 * raw socket components directly.
 *
 * Create via [connect] for new connections or [wrap] for pre-existing sockets (e.g., tests).
 */
class SocketConnection private constructor(
    val socket: ClientToServerSocket,
    val pool: BufferPool,
    val stream: SuspendingStreamProcessor,
    private val options: ConnectionOptions,
) : SuspendCloseable {
    val isOpen: Boolean get() = socket.isOpen()

    /**
     * Reads data from socket into the stream processor using a pooled buffer.
     *
     * Uses zero-copy path: acquires a buffer from the pool, reads directly into it,
     * then transfers ownership to the stream processor. The buffer is freed if the
     * read fails or returns no data.
     */
    suspend fun readIntoStream(timeout: Duration = options.readTimeout): Int {
        val buffer = pool.acquire(options.defaultBufferSize)
        try {
            val bytesRead = socket.read(buffer, timeout)
            if (bytesRead > 0) {
                buffer.setLimit(buffer.position())
                buffer.position(0)
                stream.append(buffer)
            } else {
                buffer.freeIfNeeded()
            }
            return bytesRead
        } catch (e: Exception) {
            buffer.freeIfNeeded()
            throw e
        }
    }

    suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration = options.writeTimeout,
    ): Int = socket.write(buffer, timeout)

    inline fun <T> withBuffer(
        minSize: Int = 0,
        block: (ReadWriteBuffer) -> T,
    ): T {
        val buf = pool.acquire(minSize)
        return try {
            block(buf)
        } finally {
            pool.release(buf)
        }
    }

    override suspend fun close() {
        socket.close()
    }

    companion object {
        suspend fun connect(
            hostname: String,
            port: Int,
            options: ConnectionOptions = ConnectionOptions(),
        ): SocketConnection {
            val socket = ClientSocket.allocate(options.allocationZone)
            socket.open(port, options.connectionTimeout, hostname, options.socketOptions)
            val pool = BufferPool(
                maxPoolSize = options.maxPoolSize,
                threadingMode = options.threadingMode,
            )
            val stream = StreamProcessor.builder(pool).buildSuspending()
            return SocketConnection(socket, pool, stream, options)
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
            val pool = BufferPool(
                maxPoolSize = options.maxPoolSize,
                threadingMode = options.threadingMode,
            )
            val stream = StreamProcessor.builder(pool).buildSuspending()
            return SocketConnection(socket, pool, stream, options)
        }
    }
}
