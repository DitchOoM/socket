package com.ditchoom.socket.quic

import com.ditchoom.socket.ConnectionOptions
import kotlin.time.Duration

actual fun defaultQuicEngine(): QuicEngine = WasmJsQuicEngine()

private class WasmJsQuicEngine : QuicEngine {
    override suspend fun <R> connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        connectionOptions: ConnectionOptions,
        timeout: Duration,
        block: suspend QuicScope.() -> R,
    ): R =
        throw UnsupportedOperationException(
            "QUIC is not supported in wasmJs environments (no raw UDP access)",
        )

    override fun close() {}
}
