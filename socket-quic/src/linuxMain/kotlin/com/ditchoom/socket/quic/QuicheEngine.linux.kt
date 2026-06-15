package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

/**
 * Linux/native [QuicEngine] backed by Cloudflare quiche (K/N cinterop) over io_uring UDP. The
 * [withQuicConnection] / [withQuicServer] wrappers own the lifecycle; this engine just builds +
 * establishes. In Phase 2b.2 it moves to `:socket-quic-quiche` unchanged.
 */
internal object QuicheEngine : QuicEngine {
    override val capabilities: EngineCapabilities =
        EngineCapabilities(
            supportsMigration = true,
            supportsDatagrams = true,
            supportsServer = true,
        )

    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        transport: TransportConfig,
        timeout: Duration,
    ): QuicConnection = buildLinuxQuicConnection(hostname, port, quicOptions, transport, timeout)

    override suspend fun bind(
        port: Int,
        host: String?,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer = buildLinuxQuicServer(port, host, tlsConfig, quicOptions)
}

/** Linux default QUIC engine: quiche. */
internal actual val platformDefaultQuicEngine: QuicEngine = QuicheEngine
