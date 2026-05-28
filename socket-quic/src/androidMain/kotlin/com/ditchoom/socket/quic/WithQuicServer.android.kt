package com.ditchoom.socket.quic

import kotlin.time.Duration

actual suspend fun <R> withQuicServer(
    port: Int,
    host: String?,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
    timeout: Duration,
    block: suspend QuicServer.() -> R,
): R = commonJvmWithQuicServer(port, host, tlsConfig, quicOptions, timeout, block)
