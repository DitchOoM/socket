package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

/**
 * Linux/native [QuicEngine] backed by Cloudflare quiche (K/N cinterop) over io_uring UDP. The
 * `withQuicConnection` / `withQuicServer` wrappers (in `:socket-quic-default`) own the lifecycle;
 * this engine just builds + establishes.
 *
 * Public SPI: `:socket-quic-default` names this as the Linux `defaultQuicEngine` actual.
 */
object QuicheEngine : QuicEngine {
    override val capabilities: EngineCapabilities =
        EngineCapabilities(
            supportsMigration = true,
            supportsDatagrams = true,
            supportsServer = true,
            // quiche reads whatever channel it is handed, so a demultiplexed port is no different
            // to it than one it bound itself.
            supportsSharedPort = true,
        )

    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        transport: TransportConfig,
        timeout: Duration,
    ): QuicConnection {
        // Opt-in capture (QuicOptions.trace): record QUIC traffic via the driver seam, then tap the
        // client's NetworkMonitor into the same recorder. Off (trace == null) → tuning is the default.
        val recorder = traceRecorderFor(quicOptions)
        val connection =
            buildLinuxQuicConnection(hostname, port, quicOptions, transport, timeout, QuicheDriverTuning(recorderFactory = { recorder }))
        wireClientConnectivityTap(quicOptions, recorder, connection)
        // Auto-migration (QuicOptions.autoMigrateOnNetworkChange, on by default): re-home on link change.
        wireAutoMigration(quicOptions, connection)
        return connection
    }

    override suspend fun bind(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer =
        buildLinuxQuicServer(
            binding,
            tlsConfig,
            quicOptions,
            QuicheDriverTuning(recorderFactory = {
                traceRecorderFor(quicOptions)
            }),
        )
}
