package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.Receiver
import com.ditchoom.buffer.flow.Sender
import com.ditchoom.buffer.flow.StreamMux
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.CodecConnection
import com.ditchoom.socket.transport.CodecReceiver
import com.ditchoom.socket.transport.CodecSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/**
 * Adapts a [WebTransportSession] to a typed [StreamMux] using a [Codec] — the WebTransport counterpart
 * of `QuicStreamMux`, so a library can code once against [StreamMux] and run over QUIC **or**
 * WebTransport (RFC_UNIFIED_ESTABLISHMENT.md §3.4).
 *
 * Each stream becomes a typed view via the codec, tightest type per direction:
 * - [openBidirectional] → [CodecConnection] over a bidirectional WT stream ([WebTransportSession.openBidiStream]).
 * - [openUnidirectional] → [CodecSender] over an outgoing unidirectional WT stream
 *   ([WebTransportSession.openUniStream], a [com.ditchoom.buffer.flow.ByteSink]); [Sender.close] FINs it.
 * - [acceptBidirectional] → [CodecConnection] over a peer-initiated bidirectional WT stream.
 * - [acceptUnidirectional] → [CodecReceiver] over a peer-initiated incoming unidirectional WT stream
 *   (a [com.ditchoom.buffer.flow.ByteSource]).
 *
 * WebTransport already surfaces peer-initiated streams as two **separate** flows
 * ([WebTransportSession.incomingBidiStreams] / [incomingUniStreams][WebTransportSession.incomingUniStreams]),
 * so no demux router is needed (unlike QUIC's single `streams()`). Each flow is drained lazily into an
 * unbounded channel on first `accept*`, on the [scope] the enclosing block runs in; structured
 * concurrency cancels the collectors when that scope ends.
 */
class WebTransportStreamMux<T>(
    private val session: WebTransportSession,
    private val codec: Codec<T>,
    private val config: TransportConfig,
    private val scope: CoroutineScope,
    private val decodeContext: DecodeContext = DecodeContext.Empty,
    private val encodeContext: EncodeContext = EncodeContext.Empty,
) : StreamMux<T> {
    // Drain each incoming flow into a channel exactly once, lazily (a mux that only opens streams
    // never starts consuming incoming ones). `by lazy` is thread-safe so each collector starts once.
    private val bidiIncoming: ReceiveChannel<ByteStream> by lazy { drain(session.incomingBidiStreams) }
    private val uniIncoming: ReceiveChannel<ByteSource> by lazy { drain(session.incomingUniStreams) }

    override suspend fun openBidirectional(): Connection<T> =
        CodecConnection(session.openBidiStream(), codec, config, decodeContext, encodeContext)

    override suspend fun openUnidirectional(): Sender<T> = CodecSender(session.openUniStream(), codec, config, encodeContext)

    override suspend fun acceptBidirectional(): Connection<T> =
        CodecConnection(bidiIncoming.receive(), codec, config, decodeContext, encodeContext)

    override suspend fun acceptUnidirectional(): Receiver<T> = CodecReceiver(uniIncoming.receive(), codec, config, decodeContext)

    private fun <S> drain(flow: kotlinx.coroutines.flow.Flow<S>): ReceiveChannel<S> {
        val channel = Channel<S>(Channel.UNLIMITED)
        scope.launch {
            try {
                flow.collect { channel.send(it) }
            } finally {
                channel.close() // session closed: unblock any pending accept
            }
        }
        return channel
    }
}
