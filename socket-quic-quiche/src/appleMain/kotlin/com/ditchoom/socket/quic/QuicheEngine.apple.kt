package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

/**
 * Apple/native [QuicEngine] backed by Cloudflare quiche (K/N cinterop into the Apple `libquiche.a`).
 * Mirrors the linux [QuicheEngine]. The `withQuicConnection` / `withQuicServer` wrappers (in
 * `:socket-quic-default`) own the lifecycle; this engine just builds + establishes.
 *
 * This **is** the Apple `defaultQuicEngine` — there is no Network.framework-native QUIC backend
 * (the former `:socket-quic-nw` was deleted in June 2026).
 *
 * Datapath split: the client's UDP rides an `NWConnection` (`UdpSocket.connect`), chosen because
 * Network.framework reports path changes and so keeps connection migration reactive on iOS. The
 * server binds a dual-stack POSIX socket, which is what an unconnected `recvfrom` accept loop needs.
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
        // ONE monitor per connection, resolved here and handed to all three consumers below. See
        // `resolveNetworkMonitor`: sharing the instance is what keeps the observation sequence a
        // migration reports and the one `networkAtClose` reports indexing the same stream.
        val monitor = resolveNetworkMonitor(quicOptions.networkMonitor)
        val observation = ConnectionNetworkObservation.of(monitor, RealDriverClock)
        val connection =
            buildAppleQuicConnection(
                hostname,
                port,
                quicOptions,
                transport,
                timeout,
                QuicheDriverTuning(recorderFactory = { recorder }, networkObservation = observation),
            )
        observation.collectInto(connection)
        wireClientConnectivityTap(quicOptions, recorder, connection, monitor)
        // Auto-migration (QuicOptions.migration, Automatic by default): re-home on link change.
        wireAutoMigration(quicOptions, connection, monitor)
        return connection
    }

    override suspend fun bind(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer =
        buildAppleQuicServer(
            binding,
            tlsConfig,
            quicOptions,
            QuicheDriverTuning(recorderFactory = {
                traceRecorderFor(quicOptions)
            }),
        )
}
