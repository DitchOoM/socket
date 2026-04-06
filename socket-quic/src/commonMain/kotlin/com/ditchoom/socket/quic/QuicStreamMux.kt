package com.ditchoom.socket.quic

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.Receiver
import com.ditchoom.buffer.flow.Sender
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.ConnectionContext
import com.ditchoom.socket.transport.CodecConnection

/**
 * Adapts a raw [QuicConnection] to a typed [StreamMux] using a [Codec].
 *
 * Each stream opened or accepted becomes a [CodecConnection] that encodes/decodes
 * messages via the codec. The [Connection.id] is set to the QUIC stream ID for
 * cross-layer log correlation.
 */
class QuicStreamMux<T>(
    private val connection: QuicScope,
    private val codec: Codec<T>,
    private val context: ConnectionContext,
    private val decodeContext: DecodeContext = DecodeContext.Empty,
    private val encodeContext: EncodeContext = EncodeContext.Empty,
) : StreamMux<T> {
    override suspend fun openBidirectional(): Connection<T> {
        val stream = connection.openStream()
        return CodecConnection(
            stream = stream,
            codec = codec,
            context = context,
            decodeContext = decodeContext,
            encodeContext = encodeContext,
            id = stream.streamId.id,
        )
    }

    override suspend fun openUnidirectional(): Sender<T> {
        val stream = connection.openStream()
        return CodecConnection(
            stream = stream,
            codec = codec,
            context = context,
            encodeContext = encodeContext,
            id = stream.streamId.id,
        )
    }

    override suspend fun acceptBidirectional(): Connection<T> {
        val stream = connection.acceptStream()
        return CodecConnection(
            stream = stream,
            codec = codec,
            context = context,
            decodeContext = decodeContext,
            encodeContext = encodeContext,
            id = stream.streamId.id,
        )
    }

    override suspend fun acceptUnidirectional(): Receiver<T> {
        val stream = connection.acceptStream()
        return CodecConnection(
            stream = stream,
            codec = codec,
            context = context,
            decodeContext = decodeContext,
            id = stream.streamId.id,
        )
    }
}
