package com.ditchoom.socket

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


interface ClientToServerSocket : ClientSocket {
    suspend fun open(
        port: Int,
        hostname: String? = null,
        timeout: Duration = 1.seconds,
        socketOptions: SocketOptions? = null
    ): SocketOptions
}