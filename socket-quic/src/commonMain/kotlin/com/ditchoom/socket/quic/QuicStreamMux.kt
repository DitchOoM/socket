package com.ditchoom.socket.quic

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.CodecConnection
import com.ditchoom.socket.transport.OverflowPolicy
import com.ditchoom.socket.transport.TypedMuxView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
class QuicStreamMux<T> private constructor(
    private val view: TypedMuxView<T>,
) : StreamMux<T> by view {
    /**
     * @param scope writer lifetime, and [outboundCapacity]/[overflowPolicy] the outbound queue policy,
     *   for every stream this mux mints — see [CodecConnection] and [OverflowPolicy] (#382). Each QUIC
     *   stream gets its own writer, and only the caller knows what a full queue should mean for the
     *   traffic it puts on them.
     */
    constructor(
        connection: QuicScope,
        codec: Codec<T>,
        options: TransportConfig,
        scope: CoroutineScope,
        outboundCapacity: Int,
        overflowPolicy: OverflowPolicy<T>,
        decodeContext: DecodeContext = DecodeContext.Empty,
        encodeContext: EncodeContext = EncodeContext.Empty,
    ) : this(
        TypedMuxView(
            raw = QuicByteStreamMux(connection),
            codec = codec,
            scope = scope,
            outboundCapacity = outboundCapacity,
            overflowPolicy = overflowPolicy,
            config = options,
            decodeContext = decodeContext,
            encodeContext = encodeContext,
        ),
    )

    /**
     * Closes every stream this mux minted, draining each one's outbound queue first (#382). A scoped
     * session calls this before cancelling the writers' scope, so a caller that queued a frame and did
     * not close the stream itself does not lose it silently.
     */
    suspend fun closeMintedConnections() = view.closeMintedConnections()

    /**
     * Source-compatible constructor for callers written against the pre-#382 signature — see
     * [CodecConnection]'s deprecated constructor for the defaults and why migrating matters.
     */
    @Deprecated(
        message =
            "State the outbound queue policy for the streams this mux mints. This overload picks " +
                "Suspend with a default capacity and a writer scope outside your structured " +
                "concurrency (only closing each stream stops its writer). See #382.",
        replaceWith =
            ReplaceWith(
                "QuicStreamMux(connection, codec, options, scope, outboundCapacity, " +
                    "OverflowPolicy.Suspend, decodeContext, encodeContext)",
            ),
    )
    constructor(
        connection: QuicScope,
        codec: Codec<T>,
        options: TransportConfig,
        decodeContext: DecodeContext = DecodeContext.Empty,
        encodeContext: EncodeContext = EncodeContext.Empty,
    ) : this(
        connection = connection,
        codec = codec,
        options = options,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        outboundCapacity = CodecConnection.DEFAULT_OUTBOUND_CAPACITY,
        overflowPolicy = OverflowPolicy.Suspend,
        decodeContext = decodeContext,
        encodeContext = encodeContext,
    )
}
