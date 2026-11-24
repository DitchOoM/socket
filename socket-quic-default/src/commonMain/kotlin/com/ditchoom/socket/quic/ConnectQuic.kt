package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Connects to [host]:[port], racing every address the name resolves to (RFC 8305), and answers the connection once
 * its handshake completed, or why the connect was refused before any socket opened. Suspends, never blocks a thread,
 * and is safe from a main thread.
 *
 * The connection is the caller's to [close][QuicConnection.close]. [timeout] bounds each candidate's handshake; a
 * handshake that fails at every candidate raises its end as [QuicCloseException]. The peer may open streams as
 * [QuicOptions.incomingStreams] says: none by default on a client.
 */
suspend fun connectQuic(
    host: String,
    port: Int,
    options: QuicOptions,
    transport: TransportConfig = TransportConfig(),
    timeout: Duration = DEFAULT_CONNECT_TIMEOUT,
): QuicConnect<QuicConnection> = coreConnect(QuicPeer.Named(host, port), options, transport, timeout)

/** [connectQuic] to [peer]: a name, or an explicit candidate set for one identity, paced by [TransportConfig.connectPacing]. */
suspend fun connectQuic(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig = TransportConfig(),
    timeout: Duration = DEFAULT_CONNECT_TIMEOUT,
): QuicConnect<QuicConnection> = coreConnect(peer, options, transport, timeout)

/**
 * [connectQuic], then [block] as the connection's block. When [block] ends, streams opened in the plain form that are
 * still open are reset with [QuicAbortCodes.writeCut] and stopped with [QuicAbortCodes.leftUnread], and the connection
 * closes: gracefully after a return, at once after a throw. [block] did not run when the connect was refused.
 */
suspend fun <R> connectQuic(
    host: String,
    port: Int,
    options: QuicOptions,
    transport: TransportConfig = TransportConfig(),
    timeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    block: suspend (QuicConnection) -> R,
): QuicConnect<R> = coreConnect(QuicPeer.Named(host, port), options, transport, timeout, block)

/** [connectQuic]'s block form to [peer]. */
suspend fun <R> connectQuic(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig = TransportConfig(),
    timeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    block: suspend (QuicConnection) -> R,
): QuicConnect<R> = coreConnect(peer, options, transport, timeout, block)

/**
 * Binds a QUIC server on [host]:[port] with the certificate of [tls], and answers the listener, or why the serve was
 * refused before any socket opened. The listener is the caller's to [close][QuicListener.close]. Port 0 takes an
 * ephemeral port; [QuicListener.port] says which. The peer may open streams as [QuicOptions.incomingStreams] says:
 * both kinds by default on a server.
 */
suspend fun serveQuic(
    tls: QuicTlsConfig,
    options: QuicOptions,
    port: Int = 0,
    host: String = ANY_IPV4,
): QuicServe<QuicListener> = coreServe(port, host, tls, options)

/** [serveQuic], then [block] with the listener, which closes when [block] ends. */
suspend fun <R> serveQuic(
    tls: QuicTlsConfig,
    options: QuicOptions,
    port: Int = 0,
    host: String = ANY_IPV4,
    block: suspend (QuicListener) -> R,
): QuicServe<R> = coreServe(port, host, tls, options, block)

private val DEFAULT_CONNECT_TIMEOUT = 15.seconds

private const val ANY_IPV4 = "0.0.0.0"
