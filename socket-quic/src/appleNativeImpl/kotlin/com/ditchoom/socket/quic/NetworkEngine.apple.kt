package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

/**
 * Apple [QuicEngine] backed by Network.framework (system QUIC; zero app-size cost). No quiche. The
 * [withQuicConnection] / [withQuicServer] wrappers own the lifecycle; this engine just builds +
 * establishes. In Phase 2b.3 it moves to `:socket-quic-nw` unchanged.
 *
 * Network.framework does not expose controllable connection migration, so [EngineCapabilities.
 * supportsMigration] is false (a connection's `migrate()` returns [MigrationResult.Unsupported]).
 */
internal object NetworkEngine : QuicEngine {
    override val capabilities: EngineCapabilities =
        EngineCapabilities(
            supportsMigration = false,
            supportsDatagrams = true,
            supportsServer = true,
        )

    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        transport: TransportConfig,
        timeout: Duration,
    ): QuicConnection = connectQuicGroup(hostname, port, quicOptions, transport, timeout)

    override suspend fun bind(
        port: Int,
        host: String?,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer = buildAppleQuicServer(port, host, tlsConfig, quicOptions, timeout)
}
