package com.ditchoom.socket.http3

import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.QuicServer
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A bound HTTP/3 server, handed to the [withHttp3Server] lifecycle block. */
class Http3Server internal constructor(
    private val quicServer: QuicServer,
) {
    /** The port the server is bound to (resolves an ephemeral port when `port = 0` was requested). */
    val port: Int get() = quicServer.port
}

/**
 * Run an HTTP/3 server (RFC 9114) bound to [port], handling each request with [onRequest], while
 * [block] runs with the bound [Http3Server] (e.g. to read [Http3Server.port] and drive clients, or
 * `awaitCancellation()` to serve until cancelled).
 *
 * Wraps [withQuicServer] (QUIC over TLS 1.3, `h3` ALPN) + [Http3ServerConnection]: every accepted
 * connection bootstraps the server control + (optionally) QPACK streams and dispatches its request
 * streams to [onRequest] as [Http3ServerExchange]s, concurrently. The accept loop is cancelled and
 * the server torn down when [block] returns (normally or exceptionally).
 *
 * [qpackCapacity] > 0 enables dynamic QPACK (RFC 9204) in both directions; 0 (default) is static-only.
 * [quicOptions] defaults to the `h3` ALPN — keep `"h3"` in [QuicOptions.alpnProtocols] for HTTP/3.
 *
 * @throws com.ditchoom.socket.SocketException if the bind fails.
 * @throws UnsupportedOperationException on platforms without an in-process QUIC server.
 */
suspend fun <R> withHttp3Server(
    port: Int = 0,
    tlsConfig: QuicTlsConfig,
    host: String? = null,
    quicOptions: QuicOptions = QuicOptions(alpnProtocols = listOf(HTTP3_ALPN)),
    connectionOptions: TransportConfig = TransportConfig(),
    qpackCapacity: Long = 0,
    timeout: Duration = 15.seconds,
    webTransport: WebTransportOptions? = null,
    onWebTransport: (suspend WebTransportServerExchange.() -> Unit)? = null,
    onRequest: suspend Http3ServerExchange.() -> Unit,
    block: suspend Http3Server.() -> R,
): R =
    withQuicServer(port, host, tlsConfig, quicOptions.forHttp3(), timeout) {
        val server = Http3Server(this)
        coroutineScope {
            val acceptJob =
                launch {
                    connections {
                        serveHttp3(connectionOptions, qpackCapacity, webTransport, onWebTransport, onRequest)
                    }
                }
            try {
                server.block()
            } finally {
                acceptJob.cancel()
            }
        }
    }

/**
 * Serve the HTTP/3 (RFC 9114) server role over ONE already-accepted QUIC connection — the
 * per-connection counterpart of [withHttp3Server], for servers that own the listener themselves.
 *
 * The reason to own the listener is protocol demultiplexing: a single QUIC listener whose
 * [QuicOptions.alpnProtocols] offers several protocols can route each accepted connection by its
 * negotiated ALPN ([QuicScope.negotiatedAlpn] / `connectionsByAlpn`), sharing one UDP port between
 * an HTTP/3 stack and other QUIC protocols:
 *
 * ```kotlin
 * withQuicServer(port = 443, tlsConfig = tls, quicOptions = QuicOptions(listOf("h3", "my-proto"))) {
 *     connectionsByAlpn(
 *         "h3" to { serveHttp3(onRequest = { /* … */ }) },
 *         "my-proto" to { /* raw QUIC streams/datagrams */ },
 *     )
 * }
 * ```
 *
 * Bootstraps the server control + (optionally) QPACK streams on this connection and dispatches its
 * request streams to [onRequest] concurrently, exactly as [withHttp3Server] does per connection.
 * Suspends until the connection closes (or the surrounding scope is cancelled).
 *
 * Because the caller builds the [QuicOptions], the transport prerequisites [withHttp3Server]
 * applies via its internal `forHttp3()` are the caller's responsibility: keep `"h3"` in the ALPN
 * offer, and leave inbound unidirectional streams deliverable (HTTP/3's control + QPACK streams are
 * peer-initiated; on platforms where a datagram flow conflicts with inbound streams, prefer streams
 * — see [com.ditchoom.socket.quic.DatagramStreamConflictPolicy]).
 */
suspend fun QuicScope.serveHttp3(
    connectionOptions: TransportConfig = TransportConfig(),
    qpackCapacity: Long = 0,
    webTransport: WebTransportOptions? = null,
    onWebTransport: (suspend WebTransportServerExchange.() -> Unit)? = null,
    onRequest: suspend Http3ServerExchange.() -> Unit,
) = Http3ServerConnection(this, connectionOptions, qpackCapacity, onRequest, webTransport, onWebTransport).serve()
