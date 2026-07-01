package com.ditchoom.socket.quic

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.TypedMuxView

/**
 * Adapts a raw [QuicScope] to a typed [StreamMux] using a [Codec] — a [TypedMuxView] over
 * [QuicByteStreamMux].
 *
 * The raw half ([QuicByteStreamMux]) owns stream opening and the demux router; the view wraps each
 * stream in the tightest typed surface per direction ([com.ditchoom.socket.transport.CodecConnection] /
 * [com.ditchoom.socket.transport.CodecSender] / [com.ditchoom.socket.transport.CodecReceiver]).
 * [com.ditchoom.buffer.flow.Connection.id] mirrors the QUIC stream id (via
 * [com.ditchoom.socket.transport.MuxIdentified] on [QuicByteStream]) for cross-layer log correlation,
 * and buffer allocation for each stream is routed through [TransportConfig.bufferFactory].
 *
 * Heterogeneous protocols that must classify each accepted stream before choosing a decoder
 * (HTTP/3-style self-describing streams, RFC 9114 §6.2) use [QuicByteStreamMux] directly instead.
 */
class QuicStreamMux<T>(
    connection: QuicScope,
    codec: Codec<T>,
    options: TransportConfig,
    decodeContext: DecodeContext = DecodeContext.Empty,
    encodeContext: EncodeContext = EncodeContext.Empty,
) : StreamMux<T> by TypedMuxView(QuicByteStreamMux(connection), codec, options, decodeContext, encodeContext)
