package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.OverflowPolicy
import com.ditchoom.socket.transport.TypedMuxView
import kotlinx.coroutines.CoroutineScope

/**
 * Adapts a [WebTransportSession] to a typed [StreamMux] using a [Codec] — a [TypedMuxView] over
 * [WebTransportByteStreamMux], and the WebTransport counterpart of `QuicStreamMux`, so a library can
 * code once against [StreamMux] and run over QUIC **or** WebTransport
 * (RFC_UNIFIED_ESTABLISHMENT.md §3.4).
 *
 * The raw half ([WebTransportByteStreamMux]) owns stream opening and incoming-flow draining; the
 * view wraps each stream in the tightest typed surface per direction
 * ([com.ditchoom.socket.transport.CodecConnection] / [com.ditchoom.socket.transport.CodecSender] /
 * [com.ditchoom.socket.transport.CodecReceiver]).
 *
 * Heterogeneous protocols that must classify each accepted stream before choosing a decoder use
 * [WebTransportByteStreamMux] directly instead.
 */
class WebTransportStreamMux<T>(
    session: WebTransportSession,
    codec: Codec<T>,
    config: TransportConfig,
    scope: CoroutineScope,
    /** Outbound queue depth and full-queue policy for every stream — see [OverflowPolicy] (#382). */
    outboundCapacity: Int,
    overflowPolicy: OverflowPolicy<T>,
    decodeContext: DecodeContext = DecodeContext.Empty,
    encodeContext: EncodeContext = EncodeContext.Empty,
) : StreamMux<T> by TypedMuxView(
        raw = WebTransportByteStreamMux(session, scope),
        codec = codec,
        // The mux's own scope owns the stream writers: they are exactly as long-lived as the mux.
        scope = scope,
        outboundCapacity = outboundCapacity,
        overflowPolicy = overflowPolicy,
        config = config,
        decodeContext = decodeContext,
        encodeContext = encodeContext,
    )
