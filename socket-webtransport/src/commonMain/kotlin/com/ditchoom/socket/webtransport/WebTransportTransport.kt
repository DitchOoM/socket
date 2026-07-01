package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.ConnectionFailureReason
import com.ditchoom.socket.SSLHandshakeFailedException
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.SocketException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.SessionOwningByteStream
import com.ditchoom.socket.transport.SessionTransport
import com.ditchoom.socket.transport.Transport
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
 * The transport-agnostic **single-stream projection** of WebTransport (RFC_UNIFIED_ESTABLISHMENT.md
 * §3.1/§3.3): a [Transport] whose [connect] establishes a WebTransport session, opens one
 * bidirectional stream, and hands it back as a plain [ByteStream]. Closing that stream closes the
 * whole session — so it behaves like a TCP transport from a protocol library's point of view, letting
 * `CodecConnection.connect(host, port, codec, WebTransportTransport(path = "/mqtt"))` run any
 * `Codec<T>` over WebTransport with no protocol-code change.
 *
 * For multiplexing (many streams / datagrams over one session), use [WebTransportSessionTransport].
 *
 * Errors are unified onto the [SocketException] family (RFC §6.1): a WebTransport handshake/CONNECT
 * failure ([WebTransportException]) during establishment maps to [SSLHandshakeFailedException] (when it
 * names a certificate/TLS problem) or [SocketConnectionException.Other]; a peer stream reset
 * ([WebTransportStreamException]) on the projected stream maps to [SocketClosedException.ConnectionReset].
 * (The read side already surfaces peer reset as buffer-flow `ReadResult.Reset`, which `CodecConnection`
 * maps to the same type.)
 *
 * @param path the WebTransport resource path on the authority (default `/`).
 */
class WebTransportTransport(
    path: String = "/",
    options: WebTransportOptions = WebTransportOptions(),
    support: WebTransportSupport = webTransportSupport(),
) : Transport {
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
