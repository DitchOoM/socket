package com.ditchoom.socket

interface ClientToServerSocket : ClientSocket {
    /**
     * Connects to [hostname]:[port]. The [TransportConfig] is injected once at
     * [ClientSocket.allocate] time, not here — this only performs the connect.
     */
    suspend fun open(
        port: Int,
        hostname: String? = null,
    )
}
