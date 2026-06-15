package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

/**
 * A [QuicEngine] for platforms with no raw-UDP / QUIC capability (browser JS, wasmJs). Every entry
 * throws [UnsupportedOperationException] — not [TODO] — so callers using `catch (Exception)` get a
 * cleanly catchable signal. This replaces the old per-platform throwing `withQuicConnection` actuals.
 *
 * @param connectReason explanation surfaced when a client [connect] is attempted.
 * @param bindReason explanation surfaced when a server [bind] is attempted.
 */
internal class UnsupportedQuicEngine(
    private val connectReason: String,
    private val bindReason: String,
) : QuicEngine {
    override val capabilities: EngineCapabilities =
        EngineCapabilities(supportsMigration = false, supportsDatagrams = false, supportsServer = false)

    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        transport: TransportConfig,
        timeout: Duration,
    ): QuicConnection = throw UnsupportedOperationException(connectReason)

    override suspend fun bind(
        port: Int,
        host: String?,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer = throw UnsupportedOperationException(bindReason)
}
