package com.ditchoom.socket

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
@ExperimentalUnsignedTypes
interface ClientToServerSocket : ClientSocket {
    suspend fun open(
        port: UShort,
        timeout: Duration = seconds(1),
        hostname: String? = null,
        socketOptions: SocketOptions? = null
    ): SocketOptions
}