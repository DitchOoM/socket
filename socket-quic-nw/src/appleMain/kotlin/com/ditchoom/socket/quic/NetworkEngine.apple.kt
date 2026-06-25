package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

/**
 * Apple [QuicEngine] backed by Network.framework (system QUIC; zero app-size cost). No quiche. The
 * `withQuicConnection` / `withQuicServer` wrappers (in `:socket-quic-default`) own the lifecycle;
 * this engine just builds + establishes.
 *
 * Public SPI: `:socket-quic-default` names this as the Apple `defaultQuicEngine` actual.
 *
 * Network.framework does not expose controllable connection migration, so [EngineCapabilities.
 * supportsMigration] is false (a connection's `migrate()` returns [MigrationResult.Unsupported]).
 */
object NetworkEngine : QuicEngine {
    override val capabilities: EngineCapabilities =
        EngineCapabilities(
            supportsMigration = false,
            supportsDatagrams = true,
            supportsServer = true,
        )

    /**
     * Apple QUIC runs exclusively on the OS-26 Swift `NetworkConnection<QUIC>` backend
     * ([connectQuicSwift] / [buildAppleQuicSwiftServer]). The Apple deployment floor is macOS/iOS 26+,
     * so there is no legacy `nw_connection_group` fallback and no quiche-on-Apple path below 26 — the
     * single backend carries datagrams AND inbound streams on one connection (issue #173).
     */
    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        transport: TransportConfig,
        timeout: Duration,
    ): QuicConnection = connectQuicSwift(hostname, port, quicOptions, transport, timeout)

    override suspend fun bind(
        port: Int,
        host: String?,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer = buildAppleQuicSwiftServer(port, host, tlsConfig, quicOptions, timeout)
}
