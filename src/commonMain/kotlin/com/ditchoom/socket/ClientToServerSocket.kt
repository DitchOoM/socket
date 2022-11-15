package com.ditchoom.socket

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface ClientToServerSocket : ClientSocket {
    suspend fun open(
        port: Int,
        timeout: Duration = 1.seconds,
        hostname: String? = null,
        socketOptions: SocketOptions? = null
    ): SocketOptions
}
