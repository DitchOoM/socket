package com.ditchoom.socket.transport

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.ByteStreamMux
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.Receiver
import com.ditchoom.buffer.flow.Sender
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CoroutineScope

/**
 * The single-codec typed view over a raw [ByteStreamMux] — [StreamMux] as a *view*, mirroring how
 * [CodecConnection] layers a [Codec] on a [com.ditchoom.buffer.flow.ByteStream].
 *
 * This is the generic half of every typed mux adapter: a transport contributes only its raw
 * [ByteStreamMux] (open/accept raw streams), and this view turns each stream into the tightest
 * typed surface per direction ([CodecConnection] / [CodecSender] / [CodecReceiver]). Heterogeneous
 * protocols (HTTP/3-style self-describing streams) skip the view and classify raw accepted streams
 * themselves — e.g. via [com.ditchoom.buffer.flow.BufferedByteSource.peek].
 *
 * Stream ids for cross-layer log correlation ([Connection.id]) are recovered from the raw stream via
 * the [MuxIdentified] capability when the transport's streams carry one, else default to `0L`.
 */
class TypedMuxView<T>(
    val raw: ByteStreamMux,
    val codec: Codec<T>,
    /**
     * Writer lifetime for every [CodecConnection] this view mints, and the outbound queue policy they
     * are minted with. Required here for the same reason they are required on [CodecConnection]: a mux
     * view creates connections on the caller's behalf, so the caller — not the view — has to state the
     * scope those writers live in and what a full queue means for its traffic (#382).
     */
    private val scope: CoroutineScope,
    private val outboundCapacity: Int,
    private val overflowPolicy: OverflowPolicy<T>,
    private val config: TransportConfig = TransportConfig(),
    private val decodeContext: DecodeContext = DecodeContext.Empty,
    private val encodeContext: EncodeContext = EncodeContext.Empty,
) : StreamMux<T> {
    override suspend fun openBidirectional(): Connection<T> {
        val stream = raw.openBidirectional()
        return CodecConnection(
            stream = stream,
            codec = codec,
            scope = scope,
            outboundCapacity = outboundCapacity,
            overflowPolicy = overflowPolicy,
            config = config,
            decodeContext = decodeContext,
            encodeContext = encodeContext,
            id = stream.muxStreamIdOrZero(),
        )
    }

    override suspend fun openUnidirectional(): Sender<T> {
        val sink = raw.openUnidirectional()
        return CodecSender(sink, codec, config, encodeContext, id = sink.muxStreamIdOrZero())
    }

    override suspend fun acceptBidirectional(): Connection<T> {
        val stream = raw.acceptBidirectional()
        return CodecConnection(
            stream = stream,
            codec = codec,
            scope = scope,
            outboundCapacity = outboundCapacity,
            overflowPolicy = overflowPolicy,
            config = config,
            decodeContext = decodeContext,
            encodeContext = encodeContext,
            id = stream.muxStreamIdOrZero(),
        )
    }

    override suspend fun acceptUnidirectional(): Receiver<T> {
        val source = raw.acceptUnidirectional()
        return CodecReceiver(source, codec, config, decodeContext, id = source.muxStreamIdOrZero())
    }

    private fun Any.muxStreamIdOrZero(): Long = (this as? MuxIdentified)?.muxStreamId ?: 0L
}
