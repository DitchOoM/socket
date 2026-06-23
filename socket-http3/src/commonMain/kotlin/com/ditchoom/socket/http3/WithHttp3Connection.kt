package com.ditchoom.socket.http3

import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.DatagramStreamConflictPolicy
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.withQuicConnection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** ALPN protocol identifier for HTTP/3 (RFC 9114 §3.1). */
const val HTTP3_ALPN: String = "h3"

/**
 * HTTP/3 structurally requires inbound (peer-initiated) streams — the peer's control and QPACK
 * encoder/decoder streams are unidirectional and server-initiated. On platforms where a datagram
 * flow and inbound streams cannot coexist (Apple Network.framework), [DatagramStreamConflictPolicy]
 * must therefore resolve to [DatagramStreamConflictPolicy.PreferStreams] for any HTTP/3 connection,
 * regardless of what the caller passed. This is a no-op everywhere the two coexist (quiche, browsers)
 * and when [QuicOptions.datagrams] is null. Applied at the [withHttp3Connection]/[withHttp3Server]
 * boundary so every HTTP/3 and WebTransport path — including callers that build their own
 * [QuicOptions] — is correct without thinking about it.
 */
internal fun QuicOptions.forHttp3(): QuicOptions =
    if (datagramStreamConflictPolicy == DatagramStreamConflictPolicy.PreferStreams) {
        this
    } else {
        copy(datagramStreamConflictPolicy = DatagramStreamConflictPolicy.PreferStreams)
    }

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
    connectionOptions: TransportConfig = TransportConfig(),
    timeout: Duration = 15.seconds,
    maxPushId: Long = -1,
    webTransport: WebTransportOptions? = null,
    block: suspend Http3Connection.() -> R,
): R =
    withQuicConnection(hostname, port, quicOptions.forHttp3(), connectionOptions, timeout) {
        Http3Connection.bootstrap(this, connectionOptions, maxPushId, webTransport).block()
    }
