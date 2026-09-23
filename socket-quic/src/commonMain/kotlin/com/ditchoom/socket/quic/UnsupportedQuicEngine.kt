package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

/**
 * A [QuicEngine] for platforms with no raw-UDP / QUIC capability (browser JS, wasmJs). Every entry
 * throws [UnsupportedOperationException] — not [TODO] — so callers using `catch (Exception)` get a
 * cleanly catchable signal.
 *
 * @param connectReason explanation surfaced when a client [connect] is attempted.
 * @param bindReason explanation surfaced when a server [bind] is attempted.
 */
class UnsupportedQuicEngine(
    private val connectReason: String,
    private val bindReason: String,
) : QuicEngine {
    override val capabilities: EngineCapabilities =
        EngineCapabilities(supportsMigration = false, supportsDatagrams = false, supportsServer = false)

    override suspend fun connect(
        binding: QuicClientBinding,
        endpoint: QuicEndpoint,
        serverName: String,
        quicOptions: QuicOptions,
        transport: TransportConfig,
        timeout: Duration,
    ): QuicConnection = throw UnsupportedOperationException(connectReason)

    // Also overridden, so a connect on a platform with no UDP fails before it resolves a name: the
    // inherited implementation would ask the resolver first and only then find there is no engine.
    override suspend fun connect(
        binding: QuicClientBinding,
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        transport: TransportConfig,
        timeout: Duration,
    ): QuicConnection = throw UnsupportedOperationException(connectReason)

    override suspend fun bind(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer = throw UnsupportedOperationException(bindReason)
}
