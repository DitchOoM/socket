package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

// No QUIC on this platform: every connect and serve is refused with QuicRefusal.NoQuicOnThisPlatform.

internal actual suspend fun coreConnect(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
): QuicConnect<QuicConnection> = QuicConnect.Refused(QuicRefusal.NoQuicOnThisPlatform)

internal actual suspend fun <R> coreConnect(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
    block: suspend (QuicConnection) -> R,
): QuicConnect<R> = QuicConnect.Refused(QuicRefusal.NoQuicOnThisPlatform)

internal actual suspend fun coreServe(
    port: Int,
    host: String,
    tls: QuicTlsConfig,
    options: QuicOptions,
): QuicServe<QuicListener> = QuicServe.Refused(QuicRefusal.NoQuicOnThisPlatform)

internal actual suspend fun <R> coreServe(
    port: Int,
    host: String,
    tls: QuicTlsConfig,
    options: QuicOptions,
    block: suspend (QuicListener) -> R,
): QuicServe<R> = QuicServe.Refused(QuicRefusal.NoQuicOnThisPlatform)

internal actual val legacyCapabilities: EngineCapabilities
    get() = EngineCapabilities(supportsMigration = false, supportsDatagrams = false, supportsServer = false)

internal actual val legacyPeerCertificates: PeerCertificateSupport get() = PeerCertificateSupport.Unavailable

internal actual suspend fun legacyConnect(
    binding: QuicClientBinding,
    endpoint: QuicEndpoint,
    serverName: String,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
): QuicScope = throw QuicRefusedException(QuicRefusal.NoQuicOnThisPlatform)

internal actual suspend fun legacyBind(
    binding: QuicPortBinding,
    tls: QuicTlsConfig,
    options: QuicOptions,
    timeout: Duration,
): QuicServer = throw QuicRefusedException(QuicRefusal.NoQuicOnThisPlatform)

internal actual suspend fun legacyBind(
    binding: QuicPortBinding,
    certificate: PeerCertificate,
    options: QuicOptions,
    timeout: Duration,
): QuicServer = throw QuicRefusedException(QuicRefusal.NoQuicOnThisPlatform)
