package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

// The QUIC stack of this platform: the core, and the legacy QuicScope stack its entry points still reach. On a platform
// with no QUIC every call is refused with QuicRefusal.NoQuicOnThisPlatform.

/** [connectQuic] on this platform's core. */
internal expect suspend fun coreConnect(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
): QuicConnect<QuicConnection>

/** [connectQuic]'s block form on this platform's core. */
internal expect suspend fun <R> coreConnect(
    peer: QuicPeer,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
    block: suspend (QuicConnection) -> R,
): QuicConnect<R>

/** [serveQuic] on this platform's core. */
internal expect suspend fun coreServe(
    port: Int,
    host: String,
    tls: QuicTlsConfig,
    options: QuicOptions,
): QuicServe<QuicListener>

/** [serveQuic]'s block form on this platform's core. */
internal expect suspend fun <R> coreServe(
    port: Int,
    host: String,
    tls: QuicTlsConfig,
    options: QuicOptions,
    block: suspend (QuicListener) -> R,
): QuicServe<R>

/** [connectQuic] over a channel the caller lent, on this platform's core. */
@OptIn(ExperimentalDatagramApi::class)
internal expect suspend fun coreConnectOver(
    channel: AddressedDatagramChannel,
    peer: QuicEndpoint,
    serverName: String,
    options: QuicOptions,
    timeout: Duration,
): QuicConnect<QuicConnection>

/** [serveQuic] over a channel the caller lent, on this platform's core. */
@OptIn(ExperimentalDatagramApi::class)
internal expect suspend fun coreServeOver(
    channel: AddressedDatagramChannel,
    tls: QuicTlsConfig,
    options: QuicOptions,
): QuicServe<QuicListener>

/** What the legacy stack supports, independent of any connection. */
internal expect val legacyCapabilities: EngineCapabilities

/** Whether the legacy stack can mint a [PeerCertificate]. */
internal expect val legacyPeerCertificates: PeerCertificateSupport

/** One legacy connect to [endpoint], established; a failure or a cancellation leaves nothing behind. */
internal expect suspend fun legacyConnect(
    binding: QuicClientBinding,
    endpoint: QuicEndpoint,
    serverName: String,
    options: QuicOptions,
    transport: TransportConfig,
    timeout: Duration,
): QuicScope

/** A legacy server bound as [binding] says, with the certificate of [tls]. */
internal expect suspend fun legacyBind(
    binding: QuicPortBinding,
    tls: QuicTlsConfig,
    options: QuicOptions,
    timeout: Duration,
): QuicServer

/** A legacy server bound as [binding] says, presenting [certificate]. */
internal expect suspend fun legacyBind(
    binding: QuicPortBinding,
    certificate: PeerCertificate,
    options: QuicOptions,
    timeout: Duration,
): QuicServer
