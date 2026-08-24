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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel

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
     *
     * ⚠️ Bidirectional streams only. [openUnidirectional] returns a [CodecSender], which still encodes
     * and writes on the **caller's** coroutine and therefore still carries all three #382 defects —
     * concurrent senders interleave, a cancelled caller truncates, and the caller waits on the peer.
     * These parameters do not reach it. Tracked separately; do not read the guarantee as mux-wide.
     */
    private val scope: CoroutineScope,
    private val outboundCapacity: Int,
    private val overflowPolicy: OverflowPolicy<T>,
    private val config: TransportConfig = TransportConfig(),
    private val decodeContext: DecodeContext = DecodeContext.Empty,
    private val encodeContext: EncodeContext = EncodeContext.Empty,
) : StreamMux<T> {
    /**
     * Source-compatible constructor for callers written against the pre-#382 signature — see
     * [CodecConnection]'s deprecated constructor for what the defaults are and why migrating matters.
     */
    @Deprecated(
        message =
            "State the outbound queue policy for the connections this view mints. This overload picks " +
                "Suspend with a default capacity and a writer scope outside your structured " +
                "concurrency (only closing each connection stops its writer). See #382.",
        replaceWith =
            ReplaceWith(
                "TypedMuxView(raw, codec, scope, outboundCapacity, OverflowPolicy.Suspend, " +
                    "config, decodeContext, encodeContext)",
            ),
    )
    constructor(
        raw: ByteStreamMux,
        codec: Codec<T>,
        config: TransportConfig = TransportConfig(),
        decodeContext: DecodeContext = DecodeContext.Empty,
        encodeContext: EncodeContext = EncodeContext.Empty,
    ) : this(
        raw = raw,
        codec = codec,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        outboundCapacity = CodecConnection.DEFAULT_OUTBOUND_CAPACITY,
        overflowPolicy = OverflowPolicy.Suspend,
        config = config,
        decodeContext = decodeContext,
        encodeContext = encodeContext,
    )

    /**
     * Every [Connection] this view has minted and not yet closed, so [closeMintedConnections] can drain
     * them (#382).
     *
     * Needed because `send` is now a hand-off: a scoped session like
     * [MultiplexingTransport.withMux] that cancelled its writer scope on exit would silently discard
     * anything a caller had queued but not flushed — and before #382, `send` returning meant written,
     * so `withMux { openBidirectional().send(x) }` was a correct program.
     *
     * An UNLIMITED channel rather than a list plus a lock: minting can happen from any coroutine, and
     * this needs to be safe on Kotlin/Native too, where a shared stdlib collection is not.
     */
    private val minted = Channel<Connection<T>>(Channel.UNLIMITED)

    /**
     * Closes every connection this view minted, which drains each one's outbound queue (bounded by
     * `TransportConfig.io.outboundDrainOnClose`). Sequential on purpose: a caller that opened many
     * streams gets a predictable teardown order, and the per-connection bound already caps the total.
     *
     * Idempotent, and safe to call when nothing was minted.
     */
    suspend fun closeMintedConnections() {
        while (true) {
            val next = minted.tryReceive()
            if (!next.isSuccess) return
            // One stream's teardown must not prevent the rest from draining.
            runCatching { next.getOrThrow().close() }
        }
    }

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
        ).also { minted.trySend(it) }
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
        ).also { minted.trySend(it) }
    }

    override suspend fun acceptUnidirectional(): Receiver<T> {
        val source = raw.acceptUnidirectional()
        return CodecReceiver(source, codec, config, decodeContext, id = source.muxStreamIdOrZero())
    }

    private fun Any.muxStreamIdOrZero(): Long = (this as? MuxIdentified)?.muxStreamId ?: 0L
}
