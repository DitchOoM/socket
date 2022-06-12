package com.ditchoom.socket

import com.ditchoom.buffer.SuspendCloseable

interface ServerSocket : SuspendCloseable {
    suspend fun bind(
        port: UShort? = null,
        host: String? = null,
        socketOptions: SocketOptions? = null,
        backlog: Int = 0
    ): SocketOptions

    suspend fun accept(): ClientSocket
    fun isOpen(): Boolean
    fun port(): UShort?
}

expect fun asyncServerSocket(): ServerSocket