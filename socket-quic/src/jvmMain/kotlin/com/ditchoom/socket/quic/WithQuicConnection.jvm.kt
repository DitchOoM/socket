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
): R = commonJvmWithQuicConnection(hostname, port, quicOptions, connectionOptions, timeout, block = block)
