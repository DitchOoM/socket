package com.ditchoom.socket

interface ClientToServerSocket : ClientSocket {
    suspend fun open(
        port: Int,
        hostname: String? = null,
        config: TransportConfig = TransportConfig(),
    )
}
