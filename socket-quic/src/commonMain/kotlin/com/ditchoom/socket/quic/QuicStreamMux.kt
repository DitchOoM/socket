package com.ditchoom.socket.quic

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.OverflowPolicy
import com.ditchoom.socket.transport.TypedMuxView
import kotlinx.coroutines.CoroutineScope

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
    /**
     * Writer lifetime and outbound queue policy for every stream this mux mints — see
     * [com.ditchoom.socket.transport.CodecConnection] and [OverflowPolicy] (#382). Required rather
     * than defaulted: each QUIC stream gets its own writer, and only the caller knows what a full
     * queue should mean for the traffic it puts on them.
     */
    scope: CoroutineScope,
    outboundCapacity: Int,
    overflowPolicy: OverflowPolicy<T>,
    decodeContext: DecodeContext = DecodeContext.Empty,
    encodeContext: EncodeContext = EncodeContext.Empty,
) : StreamMux<T> by TypedMuxView(
        raw = QuicByteStreamMux(connection),
        codec = codec,
        scope = scope,
        outboundCapacity = outboundCapacity,
        overflowPolicy = overflowPolicy,
        config = options,
        decodeContext = decodeContext,
        encodeContext = encodeContext,
    )
