package com.ditchoom.socket.quic

import kotlin.time.Duration

/**
 * JS QUIC server engine — placeholder. See [JsQuicEngine] for rationale;
 * same deferral applies. [bind] throws [UnsupportedOperationException] so
 * callers can catch Exception cleanly instead of an Error from [TODO].
 */
actual fun defaultQuicServerEngine(): QuicServerEngine = JsQuicServerEngine()

private class JsQuicServerEngine : QuicServerEngine {
    override suspend fun bind(
        port: Int,
        host: String?,
        tlsConfig: QuicTlsConfig,
        quicOptions: QuicOptions,
        timeout: Duration,
    ): QuicServer =
        throw UnsupportedOperationException(
            "QUIC server is not yet implemented on JS. Track feature/socket-quic-js-wip for progress.",
        )

    override fun close() {}
}
