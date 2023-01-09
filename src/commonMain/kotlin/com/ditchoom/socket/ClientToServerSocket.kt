package com.ditchoom.socket

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface ClientToServerSocket : ClientSocket {
    suspend fun open(
        port: Int,
        timeout: Duration = 15.seconds,
        hostname: String? = null
    )
}
