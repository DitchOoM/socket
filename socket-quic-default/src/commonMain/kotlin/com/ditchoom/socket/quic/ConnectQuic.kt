package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Connects to [host]:[port], racing every address the name resolves to (RFC 8305), and answers the connection once its
 * handshake completed, or why there is none: [QuicConnect.Refused] before any socket opened, [QuicConnect.Failed] with
 * the end of the last attempt. No outcome is an exception; only a cancellation raises. Suspends, never blocks a
 * thread, and is safe from a main thread.
 *
 * The connection is the caller's to [close][QuicConnection.close]. [timeout] bounds each candidate's handshake. The
 * peer may open streams as [QuicOptions.incomingStreams] says: none by default on a client.
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
 * Binds a QUIC server on [host]:[port] with the certificate of [tls], and answers the listener, or why nothing is served:
 * [QuicServe.Refused] before any socket opened, [QuicServe.Failed] when the socket could not be opened. The default
 * host `::` binds both families: IPv4 clients reach it at their mapped addresses. The listener is the caller's to [close][QuicListener.close]. Port 0 takes an
 * ephemeral port; [QuicListener.port] says which. The peer may open streams as [QuicOptions.incomingStreams] says:
 * both kinds by default on a server.
 */
suspend fun serveQuic(
    tls: QuicTlsConfig,
    options: QuicOptions,
    port: Int = 0,
    host: String = ANY_ADDRESS,
): QuicServe<QuicListener> = coreServe(port, host, tls, options)

/** [serveQuic], then [block] with the listener, which closes when [block] ends. */
suspend fun <R> serveQuic(
    tls: QuicTlsConfig,
    options: QuicOptions,
    port: Int = 0,
    host: String = ANY_ADDRESS,
    block: suspend (QuicListener) -> R,
): QuicServe<R> = coreServe(port, host, tls, options, block)

/**
 * Connects to [peer] over [channel], a datagram channel the caller bound and keeps: the QUIC branch of a port it shares
 * with ICE and STUN (`MultiplexedUdpSocket.quic` of `:socket-udp`), for direct peer-to-peer QUIC. One attempt, no race;
 * [serverName] is the identity presented and verified. The connection never closes [channel] and cannot move off it, so
 * closing the connection leaves the port serving everything else on it. GREASE of the fixed bit is off, since a greased
 * first byte lands in the DTLS and STUN ranges (RFC 9443 section 3). A channel carries one QUIC role at a time, a client
 * or a server: while one holds it, this is [QuicConnect.Refused] with [QuicRefusal.ChannelInUse]. Answers as
 * [connectQuic] does.
 */
@OptIn(ExperimentalDatagramApi::class)
suspend fun connectQuic(
    channel: AddressedDatagramChannel,
    peer: QuicEndpoint,
    serverName: String,
    options: QuicOptions,
    timeout: Duration = DEFAULT_CONNECT_TIMEOUT,
): QuicConnect<QuicConnection> = coreConnectOver(channel, peer, serverName, options, timeout)

/**
 * Serves QUIC over [channel], a datagram channel the caller bound and keeps, such as the QUIC branch of a port it shares
 * with ICE and STUN. The listener never closes [channel]: closing it leaves the port serving everything else on it.
 * GREASE of the fixed bit is off, and the channel carries one QUIC role at a time, as for [connectQuic] over a channel.
 * Answers as [serveQuic] does.
 */
@OptIn(ExperimentalDatagramApi::class)
suspend fun serveQuic(
    channel: AddressedDatagramChannel,
    tls: QuicTlsConfig,
    options: QuicOptions,
): QuicServe<QuicListener> = coreServeOver(channel, tls, options)

private val DEFAULT_CONNECT_TIMEOUT = 15.seconds

private const val ANY_ADDRESS = "::"
