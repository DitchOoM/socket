package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration

actual suspend fun <R> withQuicConnection(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    connectionOptions: TransportConfig,
    timeout: Duration,
    block: suspend QuicScope.() -> R,
): R = throw UnsupportedOperationException("QUIC is not supported in wasmJs environments (no raw UDP access)")
