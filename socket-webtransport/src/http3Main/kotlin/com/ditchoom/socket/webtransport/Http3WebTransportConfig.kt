package com.ditchoom.socket.webtransport

import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.QuicOptions

/**
 * Native-only WebTransport client configuration — the full-control escape hatch for jvm/android/native
 * callers (Fork "option 3"). Where the neutral [WebTransportOptions] exposes only knobs every backing
 * (including the browser) can honor, this carries the platform-specific QUIC/TLS surface that a browser
 * build has no business seeing: custom transport parameters, peer-verification policy, pinned trust
 * anchors, and the connection's buffer/IO policy.
 *
 * It lives in the native (`http3Main`) source set and is reached through the native overloads of
 * [connect] / [connectMultiplexed] (the [WebTransportSupport]/[WebTransportSupport.Multiplexed]
 * extensions below), so it never appears on the common API and the browser build can't reference it —
 * exactly the honesty-by-construction the v6 capability model is built on. Common code that only needs
 * cross-platform trust uses [WebTransportOptions] (and, once landed, its neutral
 * `serverCertificateHashes`); native code that needs more reaches for this.
 *
 * @property quicOptions full QUIC transport configuration. When null, a default is used
 *   (`h3` ALPN + DATAGRAM support). When provided, it is used **as given** — so it MUST keep `"h3"` in
 *   [QuicOptions.alpnProtocols] (WebTransport requires HTTP/3) and should enable
 *   [QuicOptions.datagrams] if the session will use datagrams.
 * @property connectionOptions HTTP/3 connection options (buffer factory, read/write policies). The
 *   buffer factory matters on Kotlin/Native, where QUIC stream I/O reads each buffer's native address,
 *   so a native-memory-backed factory is required for zero-copy correctness.
 */
class Http3WebTransportConfig(
    val quicOptions: QuicOptions? = null,
    val connectionOptions: TransportConfig = TransportConfig(),
)

/**
 * Native overload of [WebTransportSupport.connect] taking full [Http3WebTransportConfig] control.
 * Available only on jvm/android/native (this is an `http3Main` extension); the browser build never sees
 * it. The receiver is always the native provider returned by [webTransportSupport].
 */
suspend fun WebTransportSupport.connect(
    url: String,
    config: Http3WebTransportConfig,
): WebTransportSession = (this as Http3WebTransportSupport).connectInternal(url, config)

/**
 * Native overload of [WebTransportSupport.Multiplexed.connectMultiplexed] taking full
 * [Http3WebTransportConfig] control. `http3Main`-only; the receiver is the native provider from
 * [webTransportSupport].
 */
suspend fun WebTransportSupport.Multiplexed.connectMultiplexed(
    url: String,
    config: Http3WebTransportConfig,
): MultiplexedWebTransport = (this as Http3WebTransportSupport).connectMultiplexedInternal(url, config)
