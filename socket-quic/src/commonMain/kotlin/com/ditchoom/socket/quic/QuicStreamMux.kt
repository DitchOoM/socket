package com.ditchoom.socket.quic

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.Receiver
import com.ditchoom.buffer.flow.Sender
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.CodecConnection
import com.ditchoom.socket.transport.CodecReceiver
import com.ditchoom.socket.transport.CodecSender
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Adapts a raw [QuicScope] to a typed [StreamMux] using a [Codec].
 *
 * Each stream becomes a typed view via the codec, with the tightest type for its direction:
 * - [openBidirectional]/[acceptBidirectional] → [CodecConnection] over a bidirectional QUIC stream.
 * - [openUnidirectional] → [CodecSender] over a locally-opened **unidirectional** QUIC stream
 *   ([QuicScope.openUniStream]). [Sender.close] FINs it.
 * - [acceptUnidirectional] → [CodecReceiver] over a peer-initiated unidirectional QUIC stream.
 *
 * The two `accept*` methods are genuinely multiplexed: a single demux router collects
 * [QuicScope.streams] once and routes each peer-initiated stream to the bidirectional or
 * unidirectional queue by [QuicStreamId.isUnidirectional], so a bidi accept and a uni accept can be
 * outstanding concurrently without racing over a shared `acceptStream()`. The router is launched
 * lazily (only when an `accept*` is first called) on the connection scope, so a mux that only opens
 * streams never starts consuming incoming ones; structured concurrency cancels it when the scope ends.
 *
 * The [Connection.id]/[Sender.id] is set to the QUIC stream id for cross-layer log correlation, and
 * buffer allocation for each stream is routed through [TransportConfig.bufferFactory].
 */
class QuicStreamMux<T>(
    private val connection: QuicScope,
    private val codec: Codec<T>,
    private val options: TransportConfig,
    private val decodeContext: DecodeContext = DecodeContext.Empty,
    private val encodeContext: EncodeContext = EncodeContext.Empty,
) : StreamMux<T> {
    private val bidiIncoming = Channel<QuicByteStream>(Channel.UNLIMITED)
    private val uniIncoming = Channel<QuicByteStream>(Channel.UNLIMITED)

    // Lazily launched on first accept*; `by lazy` is thread-safe so the router starts exactly once.
    private val router: Job by lazy {
        connection.launch {
            try {
                connection.streams().collect { stream ->
                    if (stream.streamId.isUnidirectional) {
                        uniIncoming.send(stream)
                    } else {
                        bidiIncoming.send(stream)
                    }
                }
            } finally {
                // Connection closed: unblock any pending accept with a closed-channel signal.
                bidiIncoming.close()
                uniIncoming.close()
            }
        }
    }

    /** Force the lazy router to initialize and start (idempotent). */
    private fun ensureRouter() {
        router.start()
    }

    override suspend fun openBidirectional(): Connection<T> {
        val stream = connection.openStream()
        return CodecConnection(
            stream = stream,
            codec = codec,
            config = options,
            decodeContext = decodeContext,
            encodeContext = encodeContext,
            id = stream.streamId.id,
        )
    }

    override suspend fun openUnidirectional(): Sender<T> {
        val stream = connection.openUniStream()
        return CodecSender(
            sink = stream,
            codec = codec,
            config = options,
            encodeContext = encodeContext,
            id = stream.streamId.id,
        )
    }

    override suspend fun acceptBidirectional(): Connection<T> {
        ensureRouter()
        val stream = bidiIncoming.receive()
        return CodecConnection(
            stream = stream,
            codec = codec,
            config = options,
            decodeContext = decodeContext,
            encodeContext = encodeContext,
            id = stream.streamId.id,
        )
    }

    override suspend fun acceptUnidirectional(): Receiver<T> {
        ensureRouter()
        val stream = uniIncoming.receive()
        return CodecReceiver(
            source = stream,
            codec = codec,
            config = options,
            decodeContext = decodeContext,
            id = stream.streamId.id,
        )
    }
}
