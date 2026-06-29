package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

/**
 * Apple/native [QuicEngine] backed by Cloudflare quiche (K/N cinterop into the macOS libquiche.a) over
 * a POSIX UDP datapath. Phase-0 quiche-on-Apple pivot: mirrors the linux [QuicheEngine]. The
 * `withQuicConnection` / `withQuicServer` wrappers (in `:socket-quic-default`) own the lifecycle; this
 * engine just builds + establishes.
 *
 * Not yet wired as the Apple `defaultQuicEngine` (that stays the NW `NetworkEngine` until the pivot's
 * later phases); consumers select it explicitly for now. See [AppleUdpChannel] for the POSIX-vs-NW
 * datapath note.
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
    ): QuicConnection = buildAppleQuicConnection(hostname, port, quicOptions, transport, timeout)

    override suspend fun bind(
        port: Int,
        host: String?,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer = buildAppleQuicServer(port, host, tlsConfig, quicOptions)
}
