package com.ditchoom.socket.quic

/**
 * A bound QUIC server that accepts incoming connections.
 *
 * Each accepted connection is handled via a scope-based callback.
 * When the callback returns, the connection is closed. If the connection
 * dies, the callback is cancelled.
 */
interface QuicServer {
    /** The port the server is bound to. */
    val port: Int

    /**
     * Accept connections and run [handler] for each.
     *
     * Each connection gets its own [QuicScope]. The handler runs after the
     * handshake completes. When the handler returns, the connection is closed.
     * Multiple connections are handled concurrently.
     *
     * Suspends until the server is closed.
     */
    suspend fun connections(handler: suspend QuicScope.() -> Unit)

    /** Stop accepting connections and close the server. */
    suspend fun close()
}
