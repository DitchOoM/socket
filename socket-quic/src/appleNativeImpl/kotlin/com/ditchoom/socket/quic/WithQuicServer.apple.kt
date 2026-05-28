package com.ditchoom.socket.quic

import kotlin.time.Duration

actual suspend fun <R> withQuicServer(
    port: Int,
    host: String?,
    tlsConfig: QuicTlsConfig,
    quicOptions: QuicOptions,
    timeout: Duration,
    block: suspend QuicServer.() -> R,
): R = TODO("Apple QUIC server — pending Network.framework NWListener implementation")
