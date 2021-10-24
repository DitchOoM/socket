@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.buffer.SuspendCloseable
import kotlin.time.ExperimentalTime

interface ServerSocket : SuspendCloseable {
    suspend fun bind(
        port: UShort? = null,
        host: String? = null,
        socketOptions: SocketOptions? = null,
        backlog: UInt = 0.toUInt()
    ): SocketOptions

    @ExperimentalTime
    suspend fun accept(): ClientSocket
    fun isOpen(): Boolean
    fun port(): UShort?
}

expect fun asyncServerSocket(): ServerSocket

expect suspend fun readStats(port: UShort, contains: String): List<String>