package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.data.Reader
import com.ditchoom.data.Writer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A client socket that can connect to a remote server.
 * Provides both reading and writing capabilities with suspend functions.
 */
interface ClientSocket :
    SocketController,
    Reader,
    Writer {
    /**
     * Controls how internal read buffers are allocated.
     * Set before first read to inject pooled, shared memory, or managed allocation strategies.
     * Platforms that use zero-copy reads (Apple, JS) ignore this property.
     */
    var bufferFactory: BufferFactory
        get() = BufferFactory.deterministic()
        set(_) {} // No-op default; overridden by JVM/Linux

    companion object
}

suspend fun ClientSocket.Companion.connect(
    port: Int,
    hostname: String? = null,
    timeout: Duration = 15.seconds,
    socketOptions: SocketOptions = SocketOptions(),
): ClientToServerSocket {
    val socket = ClientSocket.allocate()
    socket.open(port, timeout, hostname, socketOptions)
    return socket
}

suspend fun <T> ClientSocket.Companion.connect(
    port: Int,
    hostname: String? = null,
    timeout: Duration = 15.seconds,
    socketOptions: SocketOptions = SocketOptions(),
    lambda: suspend (ClientSocket) -> T,
): T {
    val socket = ClientSocket.allocate()
    return try {
        socket.open(port, timeout, hostname, socketOptions)
        lambda(socket)
    } finally {
        socket.close()
    }
}

expect fun ClientSocket.Companion.allocate(): ClientToServerSocket
