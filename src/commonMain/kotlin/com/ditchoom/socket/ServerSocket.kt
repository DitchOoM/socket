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

/**
 * Allocates the platform server socket, injecting [config] at allocation time. The config is applied
 * to every socket this server accepts (read/write policy, buffer factory, I/O tuning), so accepted
 * connections obey the same contract as client connections. Mirrors [ClientSocket.allocate].
 */
expect fun ServerSocket.Companion.allocate(config: TransportConfig = TransportConfig()): ServerSocket
