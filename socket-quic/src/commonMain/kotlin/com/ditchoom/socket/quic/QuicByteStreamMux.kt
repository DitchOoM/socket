package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ByteStreamMux
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Adapts a raw [QuicScope] to a [ByteStreamMux] — the codec-free mux primitive. Streams come back
 * raw ([QuicByteStream]), so heterogeneous protocols (HTTP/3-style self-describing streams) can
 * classify each accepted stream before choosing a decoder; the single-codec typed surface is
 * `QuicStreamMux`, a [com.ditchoom.socket.transport.TypedMuxView] over this adapter.
 *
 * The two `accept*` methods are genuinely multiplexed: a single demux router collects
 * [QuicScope.streams] once and routes each peer-initiated stream to the bidirectional or
 * unidirectional queue by [QuicStreamId.isUnidirectional], so a bidi accept and a uni accept can be
 * outstanding concurrently without racing over a shared `acceptStream()`. The router is launched
 * lazily (only when an `accept*` is first called) on the connection scope, so a mux that only opens
 * streams never starts consuming incoming ones; structured concurrency cancels it when the scope ends.
 *
 * Every returned stream is a [QuicByteStream], which carries its QUIC stream id
 * ([com.ditchoom.socket.transport.MuxIdentified]) for cross-layer log correlation.
 */
class QuicByteStreamMux(
    private val connection: QuicScope,
) : ByteStreamMux {
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

    override suspend fun openBidirectional(): ByteStream = connection.openStream()

    override suspend fun openUnidirectional(): ByteSink = connection.openUniStream()

    override suspend fun acceptBidirectional(): ByteStream {
        ensureRouter()
        return bidiIncoming.receive()
    }

    override suspend fun acceptUnidirectional(): ByteSource {
        ensureRouter()
        return uniIncoming.receive()
    }
}
