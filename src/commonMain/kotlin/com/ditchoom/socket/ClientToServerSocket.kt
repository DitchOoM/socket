package com.ditchoom.socket

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@ExperimentalTime
@ExperimentalUnsignedTypes
interface ClientToServerSocket : ClientSocket {
    suspend fun open(
        port: UShort,
        timeout: Duration = 1.seconds,
        hostname: String? = null,
        socketOptions: SocketOptions? = null
    ): SocketOptions
}