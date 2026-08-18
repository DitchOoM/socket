package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

/**
 * JVM/Android [QuicEngine] backed by Cloudflare quiche (JNI on JDK 8–20, FFM on JDK 21+, selected
 * by [loadQuicheApi]) over NIO [java.nio.channels.DatagramChannel]. The `withQuicConnection` /
 * `withQuicServer` wrappers (in `:socket-quic-default`) own the lifecycle; this engine just builds
 * + establishes.
 *
 * Public SPI: `:socket-quic-default` names this as the JVM/Android `defaultQuicEngine` actual.
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
            buildJvmQuicConnection(
                hostname,
                port,
                quicOptions,
                transport,
                timeout,
                loadQuicheApi(),
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
        buildJvmQuicServer(
            binding,
            tlsConfig,
            quicOptions,
            QuicheDriverTuning(recorderFactory = {
                traceRecorderFor(quicOptions)
            }),
        )
}
