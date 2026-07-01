package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ByteStreamMux
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Adapts a [WebTransportSession] to a [ByteStreamMux] — the codec-free mux primitive, and the
 * WebTransport counterpart of `QuicByteStreamMux`. Streams come back raw, so heterogeneous
 * protocols can classify each accepted stream before choosing a decoder; the single-codec typed
 * surface is `WebTransportStreamMux`, a [com.ditchoom.socket.transport.TypedMuxView] over this
 * adapter.
 *
 * WebTransport already surfaces peer-initiated streams as two **separate** flows
 * ([WebTransportSession.incomingBidiStreams] / [incomingUniStreams][WebTransportSession.incomingUniStreams]),
 * so no demux router is needed (unlike QUIC's single `streams()`). Each flow is drained lazily into an
 * unbounded channel on first `accept*`, on the [scope] the enclosing block runs in; structured
 * concurrency cancels the collectors when that scope ends.
 */
class WebTransportByteStreamMux(
    private val session: WebTransportSession,
    private val scope: CoroutineScope,
) : ByteStreamMux {
    // Drain each incoming flow into a channel exactly once, lazily (a mux that only opens streams
    // never starts consuming incoming ones). `by lazy` is thread-safe so each collector starts once.
    private val bidiIncoming: ReceiveChannel<ByteStream> by lazy { drain(session.incomingBidiStreams) }
    private val uniIncoming: ReceiveChannel<ByteSource> by lazy { drain(session.incomingUniStreams) }

    override suspend fun openBidirectional(): ByteStream = session.openBidiStream()

    override suspend fun openUnidirectional(): ByteSink = session.openUniStream()

    override suspend fun acceptBidirectional(): ByteStream = bidiIncoming.receive()

    override suspend fun acceptUnidirectional(): ByteSource = uniIncoming.receive()

    private fun <S> drain(flow: Flow<S>): ReceiveChannel<S> {
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
