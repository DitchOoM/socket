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
     * The ALPN protocols this listener offered at bind time ([QuicOptions.alpnProtocols]) — the exact
     * set a peer can end up negotiating, since TLS only ever selects from the server's own offer and
     * fails the handshake with `no_application_protocol` when the intersection is empty.
     *
     * That makes it the authority [connectionsByAlpn] validates its routing table against: a protocol
     * offered here but left unrouted is a local configuration bug, and it is the only way an accepted
     * connection can reach `onUnmatched`.
     *
     * Empty means *not reported* (a test double, or an implementation that does not track its offer);
     * validation is skipped in that case rather than failing closed.
     */
    val alpnProtocols: List<String> get() = emptyList()

    /**
     * Accept connections and run [handler] for each.
     *
     * Each connection gets its own [QuicScope]. The handler runs once the handshake completes or,
     * with [QuicOptions.enableEarlyData], as soon as the client's 0-RTT is accepted — so a request sent
     * early can be answered in the server's first flight. Streams the client opened early are
     * [QuicStreamOrigin.Early], and reading one waits for the handshake to be confirmed unless the
     * handler calls [QuicByteStream.markReplaySafe] on it; a handshake that fails closes the connection
     * under the handler as any close does. When the handler returns, the connection is closed.
     * Multiple connections are handled concurrently.
     *
     * Suspends until the server is closed.
     */
    suspend fun connections(handler: suspend QuicScope.() -> Unit)

    /** Stop accepting connections and close the server. */
    suspend fun close()
}
