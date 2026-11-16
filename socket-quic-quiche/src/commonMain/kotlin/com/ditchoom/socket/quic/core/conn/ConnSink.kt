package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.life.ConnEnd

/** What an established connection reports once: how long the handshake took and the length of the negotiated ALPN. */
internal class ConnectReport(
    val handshakeNanos: Long,
    val alpnLength: Int,
)

/** The one upward notification of a connection. Called inside it, on the thread inside. */
internal interface ConnSink {
    fun established(report: ConnectReport)

    /** The peer opened [stream]. */
    fun incoming(stream: QuicStreamId)

    fun ended(end: ConnEnd)
}
