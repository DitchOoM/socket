package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.ConnectionFailureReason
import com.ditchoom.socket.SSLHandshakeFailedException
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.SocketException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.MultiplexingTransport
import com.ditchoom.socket.transport.SessionOwningByteStream
import com.ditchoom.socket.transport.SessionTransport
import com.ditchoom.socket.transport.Transport
import com.ditchoom.socket.transport.use
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Layer-2 multiplexed WebTransport transport (RFC_UNIFIED_ESTABLISHMENT.md §3.2): establishes a
 * held [WebTransportSession] over which the caller opens many bidi/uni streams and datagrams.
 *
 * WebTransport addresses by **URL** (`https://authority/path`), not host:port. To fit the uniform
 * [establish]/[Transport.connect] `(hostname, port, config)` shape, the URL-specific coordinates — the
 * scheme (fixed `https`) and the [path] — live on this instance (RFC §4). `establish("h", 443)` builds
 * `https://h:443$path`. This keeps a protocol library that binds to the agnostic surface entirely
 * addressing-neutral: the application pre-configures the transport with the path and the library only
 * ever passes host:port. (A caller that already holds a full URL and wants WebTransport specifically
 * can bypass the transport abstraction and use [webTransportSupport]`().connect(url)` directly.)
 *
 * [use][com.ditchoom.socket.transport.use] gives the scoped ergonomic over [establish]/[close].
 */
class WebTransportSessionTransport(
    private val path: String = "/",
    private val options: WebTransportOptions = WebTransportOptions(),
    private val support: WebTransportSupport = webTransportSupport(),
) : SessionTransport<WebTransportSession> {
    override suspend fun establish(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): WebTransportSession {
        val url = buildUrl(hostname, port, path)
        return try {
            support.connect(url, options)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebTransportException) {
            throw mapEstablishmentError(e, hostname, port)
        }
    }

    override suspend fun close(session: WebTransportSession) = session.close()
}

/**
 * The WebTransport front-door transport — implements **both** agnostic tiers
 * (RFC_UNIFIED_ESTABLISHMENT.md §3.1/§3.3/§3.4):
 *  - [Transport.connect] → the **single-stream projection**: establish a session, open one
 *    bidirectional stream, hand it back as a plain [ByteStream] whose `close()` closes the session — so
 *    it behaves like TCP and any `Codec<T>` runs over it via
 *    `CodecConnection.connect(host, port, codec, WebTransportTransport(path = "/mqtt"))`.
 *  - [MultiplexingTransport.withMux] → the **multiplex** tier: run a block with a typed [StreamMux]
 *    over the session (many concurrent bidi/uni streams), via [WebTransportStreamMux].
 *
 * Implementing both on one object is the fix for the "two-tier leak" (same type-gated capability
 * pattern as `WebTransportSupport.Multiplexed`): a library holds one [Transport] and reaches
 * multiplexing only where it exists, by `if (transport is MultiplexingTransport)` — no stubbed
 * capability. For raw session power (datagrams / hand-managed lifetime), use [WebTransportSessionTransport].
 *
 * Addressing (RFC §4): host:port on the call + [path] on the instance build `https://host:port$path`.
 * Errors are unified onto the [SocketException] family (RFC §6.1): a handshake/CONNECT failure
 * ([WebTransportException]) maps to [SSLHandshakeFailedException] (certificate/TLS) or
 * [SocketConnectionException.Other]; a peer stream reset ([WebTransportStreamException]) on the projected
 * single stream maps to [SocketClosedException.ConnectionReset]. (The read side already surfaces peer
 * reset as buffer-flow `ReadResult.Reset`, which `CodecConnection` maps to the same type.)
 *
 * @param path the WebTransport resource path on the authority (default `/`).
 */
class WebTransportTransport(
    path: String = "/",
    options: WebTransportOptions = WebTransportOptions(),
    support: WebTransportSupport = webTransportSupport(),
) : Transport,
    MultiplexingTransport {
    private val session = WebTransportSessionTransport(path, options, support)

    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream {
        val wtSession = session.establish(hostname, port, config)
        val stream =
            try {
                wtSession.openBidiStream()
            } catch (t: Throwable) {
                // Opening the first stream failed — don't leak the session we just established.
                wtSession.close()
                throw t
            }
        return SessionOwningByteStream(
            stream = stream,
            closeSession = { wtSession.close() },
            mapError = ::mapStreamError,
        )
    }

    override suspend fun <T, R> withMux(
        hostname: String,
        port: Int,
        codec: Codec<T>,
        config: TransportConfig,
        block: suspend StreamMux<T>.() -> R,
    ): R =
        // establish (WebTransportException -> SocketException) + close the session in finally.
        session.use(hostname, port, config) { wt ->
            // A child scope for the lazy incoming-stream collectors; cancelled before the session closes.
            val muxScope = CoroutineScope(currentCoroutineContext() + Job())
            try {
                WebTransportStreamMux(wt, codec, config, muxScope).block()
            } finally {
                muxScope.cancel()
            }
        }
}

/** Build the `https://authority/path` URL from host:port + the instance path (scheme is fixed `https`). */
private fun buildUrl(
    hostname: String,
    port: Int,
    path: String,
): String {
    val normalizedPath = if (path.startsWith("/")) path else "/$path"
    return "https://$hostname:$port$normalizedPath"
}

/** Map a WebTransport establishment failure to the unified [SocketException] family (RFC §6.1). */
private fun mapEstablishmentError(
    e: WebTransportException,
    hostname: String,
    port: Int,
): SocketException {
    val msg = e.message?.lowercase() ?: ""
    return when {
        msg.contains("certificate") || msg.contains("cert") || msg.contains("tls") || msg.contains("trust") ->
            SSLHandshakeFailedException(
                e.message ?: "WebTransport TLS handshake failed",
                cause = e,
                reason =
                    if (msg.contains("cert") || msg.contains("trust")) {
                        ConnectionFailureReason.TlsBadCertificate
                    } else {
                        ConnectionFailureReason.TlsHandshake
                    },
            )
        else ->
            SocketConnectionException.Other(
                ConnectionFailureReason.Unknown(e.message ?: e::class.simpleName ?: "WebTransport connect failed"),
                "WebTransport connect to $hostname:$port failed: ${e.message}",
                e,
            )
    }
}

/** Map a WebTransport stream abort to the unified connection-lost error for the single-stream surface. */
private fun mapStreamError(t: Throwable): Throwable =
    when (t) {
        is SocketException -> t // already unified
        is WebTransportStreamException ->
            SocketClosedException.ConnectionReset(
                "WebTransport stream reset by peer (code ${t.errorCode})",
                cause = t,
            )
        is WebTransportException ->
            SocketClosedException.ConnectionReset(
                "WebTransport session error: ${t.message}",
                cause = t,
            )
        else -> t
    }
