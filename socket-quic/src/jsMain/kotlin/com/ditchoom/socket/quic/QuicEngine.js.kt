package com.ditchoom.socket.quic

import com.ditchoom.socket.ConnectionOptions
import kotlin.time.Duration

actual fun defaultQuicEngine(): QuicEngine = JsQuicEngine()

private class JsQuicEngine : QuicEngine {
    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        connectionOptions: ConnectionOptions,
        timeout: Duration,
    ): QuicConnection {
        // Node.js: quiche via koffi FFI — not yet implemented
        // Browser: raw UDP is not available
        val isNode = js("typeof process !== 'undefined' && process.versions != null && process.versions.node != null")
        if (isNode as Boolean) {
            TODO("Node.js QUIC via quiche FFI (koffi) — not yet implemented")
        } else {
            throw UnsupportedOperationException(
                "QUIC is not supported in browser environments (no raw UDP access)",
            )
        }
    }

    override fun close() {}
}
