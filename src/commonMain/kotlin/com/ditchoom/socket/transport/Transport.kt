package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.TransportConfig

interface Transport {
    /**
     * The IP-level family this transport rides on, used by [FallbackTransport]'s staggered race
     * (RFC_TRANSPORT_FALLBACK §5) to draw its lanes. Defaults to [TransportFamily.Tcp] — the
     * conservative floor family — so out-of-tree transports (e.g. `WebSocketTransport`) are correct
     * without declaring anything; QUIC-family transports override to [TransportFamily.Udp].
     */
    val family: TransportFamily get() = TransportFamily.Tcp

    suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream
}
