package com.ditchoom.socket.quic

import kotlin.time.Duration

actual suspend fun <R> withQuicServer(
    port: Int,
    host: String?,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
    timeout: Duration,
    block: suspend QuicServer.() -> R,
): R = throw UnsupportedOperationException("QUIC server is not supported in WASM environments")
