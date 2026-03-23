package com.ditchoom.socket

import kotlinx.coroutines.flow.Flow

interface ServerSocket {
    suspend fun bind(
        port: Int = -1,
        host: String? = null,
        backlog: Int = 0,
    ): Flow<ClientSocket>

    fun isListening(): Boolean

    fun port(): Int

    suspend fun close()

    companion object
}

expect fun ServerSocket.Companion.allocate(): ServerSocket
