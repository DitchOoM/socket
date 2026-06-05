package com.ditchoom.socket.http3

import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.withQuicConnection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** ALPN protocol identifier for HTTP/3 (RFC 9114 §3.1). */
const val HTTP3_ALPN: String = "h3"

/**
 * Open an HTTP/3 connection to [hostname]:[port] and run [block] with the bootstrapped
 * [Http3Connection] (RFC 9114).
 *
 * Wraps [withQuicConnection] + [Http3Connection.bootstrap]: establishes QUIC over TLS 1.3 with
 * the `h3` ALPN, opens the client control + QPACK encoder/decoder unidirectional streams and
 * sends the client SETTINGS, then hands you the connection to issue [Http3Connection.request]s.
 * The QUIC connection, its streams, and the peer-stream router are all torn down when [block]
 * returns (normally or exceptionally) — the block boundary is the connection lifetime.
 *
 * [quicOptions] defaults to the `h3` ALPN; override it to tune transport parameters, but keep
 * `"h3"` in [QuicOptions.alpnProtocols] or the server won't negotiate HTTP/3.
 *
 * Server push (RFC 9114 §4.6) is opt-in via [maxPushId] (default -1 = disabled): set it >= 0 to send
 * MAX_PUSH_ID and consume server pushes through [Http3Connection.pushes].
 *
 * @throws com.ditchoom.socket.SocketConnectionException if the QUIC/TLS handshake fails.
 * @throws UnsupportedOperationException on platforms without QUIC (e.g. JS today).
 */
suspend fun <R> withHttp3Connection(
    hostname: String,
    port: Int = 443,
    quicOptions: QuicOptions = QuicOptions(alpnProtocols = listOf(HTTP3_ALPN)),
    connectionOptions: ConnectionOptions = ConnectionOptions(),
    timeout: Duration = 15.seconds,
    maxPushId: Long = -1,
    block: suspend Http3Connection.() -> R,
): R =
    withQuicConnection(hostname, port, quicOptions, connectionOptions, timeout) {
        Http3Connection.bootstrap(this, connectionOptions, maxPushId).block()
    }
