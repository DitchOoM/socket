package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.CodecConnection
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
    ) {
    /**
     * Source-compatible constructor for callers written against the pre-#382 signature — see
     * [CodecConnection]'s deprecated constructor for the defaults and why migrating matters. This one
     * keeps the caller-supplied [scope] for the writers, so only the queue policy is being defaulted.
     */
    @Deprecated(
        message =
            "State the outbound queue policy for the streams this mux mints; this overload picks " +
                "Suspend with a default capacity. See #382.",
        replaceWith =
            ReplaceWith(
                "WebTransportStreamMux(session, codec, config, scope, outboundCapacity, " +
                    "OverflowPolicy.Suspend, decodeContext, encodeContext)",
            ),
    )
    constructor(
        session: WebTransportSession,
        codec: Codec<T>,
        config: TransportConfig,
        scope: CoroutineScope,
        decodeContext: DecodeContext = DecodeContext.Empty,
        encodeContext: EncodeContext = EncodeContext.Empty,
    ) : this(
        session = session,
        codec = codec,
        config = config,
        scope = scope,
        outboundCapacity = CodecConnection.DEFAULT_OUTBOUND_CAPACITY,
        overflowPolicy = OverflowPolicy.Suspend,
        decodeContext = decodeContext,
        encodeContext = encodeContext,
    )
}
