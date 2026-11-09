package com.ditchoom.socket

/**
 * A TCP peer made with the platform's own sockets, not this library's, so a test can make it end a
 * connection in ways this library never does on purpose: an abortive close that puts a RST on the
 * wire (`SO_LINGER` 0, then close).
 */
internal interface RawTcpPeer {
    suspend fun write(bytes: ByteArray)

    /** Close gracefully: the connection ends with a FIN. */
    suspend fun close()

    /** Close abortively: `SO_LINGER` {on, 0} and close, so the connection ends with a RST. */
    suspend fun reset()
}

/** Connects a [RawTcpPeer] to `127.0.0.1:[port]`. */
internal expect suspend fun connectRawTcpPeer(port: Int): RawTcpPeer
