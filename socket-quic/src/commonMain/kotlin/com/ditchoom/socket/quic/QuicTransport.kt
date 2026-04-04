package com.ditchoom.socket.quic

import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.transport.ByteStream
import com.ditchoom.socket.transport.Transport

/**
 * [Transport] implementation backed by QUIC.
 *
 * Each [connect] call establishes a QUIC connection and opens a single
 * bidirectional stream, returning it as a [ByteStream]. For multi-stream
 * use, call [QuicEngine.connect] directly and manage streams via [QuicConnection].
 *
 * Drop-in replacement for [com.ditchoom.socket.transport.TcpTransport] —
 * works with [com.ditchoom.socket.transport.CodecConnection] and
 * [com.ditchoom.socket.transport.ReconnectingConnection] unchanged.
 */
class QuicTransport(
    private val quicOptions: QuicOptions,
    private val engine: QuicEngine = defaultQuicEngine(),
) : Transport {
    override suspend fun connect(
        hostname: String,
        port: Int,
        options: ConnectionOptions,
    ): ByteStream {
        val connection = engine.connect(hostname, port, quicOptions, options, options.connectionTimeout)
        return connection.openStream()
    }
}
