package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

// This platform's QUIC stack: the core of :socket-quic-quiche, and its legacy QuicScope stack.

internal actual suspend fun coreConnect(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
): QuicConnect<QuicConnection> = connectQuicOnCore(peer, options, transport, timeout)

internal actual suspend fun <R> coreConnect(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
    block: suspend (QuicConnection) -> R,
): QuicConnect<R> = connectQuicOnCore(peer, options, transport, timeout, block)

internal actual suspend fun coreServe(
    port: Int,
    host: String,
    tls: QuicTlsConfig,
    options: QuicOptions,
): QuicServe<QuicListener> = serveQuicOnCore(port, host, tls, options)

internal actual suspend fun <R> coreServe(
    port: Int,
    host: String,
    tls: QuicTlsConfig,
    options: QuicOptions,
    block: suspend (QuicListener) -> R,
): QuicServe<R> = serveQuicOnCore(port, host, tls, options, block)

internal actual val legacyCapabilities: EngineCapabilities get() = QuicheEngine.capabilities

internal actual val legacyPeerCertificates: PeerCertificateSupport get() = QuicheEngine.peerCertificates

internal actual suspend fun legacyConnect(
    binding: QuicClientBinding,
    endpoint: QuicEndpoint,
    serverName: String,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
): QuicScope = QuicheEngine.connect(binding, endpoint, serverName, options, transport, timeout)

internal actual suspend fun legacyBind(
    binding: QuicPortBinding,
    tls: QuicTlsConfig,
    options: QuicOptions,
    timeout: Duration,
): QuicServer = QuicheEngine.bind(binding, tls, options, timeout)

internal actual suspend fun legacyBind(
    binding: QuicPortBinding,
    certificate: PeerCertificate,
    options: QuicOptions,
    timeout: Duration,
): QuicServer = QuicheEngine.bind(binding, certificate, options, timeout)
