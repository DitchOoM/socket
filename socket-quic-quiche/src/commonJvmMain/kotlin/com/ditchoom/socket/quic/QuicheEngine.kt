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
        )

    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        transport: TransportConfig,
        timeout: Duration,
    ): QuicConnection = buildJvmQuicConnection(hostname, port, quicOptions, transport, timeout, loadQuicheApi())

    override suspend fun bind(
        port: Int,
        host: String?,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer = buildJvmQuicServer(port, host, tlsConfig, quicOptions)
}
