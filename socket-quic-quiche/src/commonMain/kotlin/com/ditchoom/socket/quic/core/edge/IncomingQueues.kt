package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.stream.StreamKind
import com.ditchoom.socket.quic.core.stream.kindOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.selects.select

/**
 * The streams the peer opened that nobody accepted yet, oldest first, in a queue per kind: an acceptor of one kind
 * never takes, or waits behind, a stream of the other.
 */
@Published
internal class IncomingQueues {
    val bidirectional = Channel<PeerStream>(Channel.UNLIMITED)
    val unidirectional = Channel<PeerStream>(Channel.UNLIMITED)

    /** Inside: the peer opened the stream [peer] names. */
    fun offer(peer: PeerStream) {
        when (kindOf(QuicStreamId(peer.id))) {
            StreamKind.Bidirectional -> bidirectional.trySend(peer)
            StreamKind.Unidirectional -> unidirectional.trySend(peer)
        }
    }

    /** The next stream of either kind, whichever is there first. */
    suspend fun receiveAny(): PeerStream =
        select {
            bidirectional.onReceive { it }
            unidirectional.onReceive { it }
        }

    /** Every stream of either kind, as each arrives; it ends when the connection ends. */
    fun all(): Flow<PeerStream> = merge(bidirectional.consumeAsFlow(), unidirectional.consumeAsFlow())

    /** The connection ended: a parked accept and every later one fail with [cause]. */
    fun close(cause: Throwable) {
        bidirectional.close(cause)
        unidirectional.close(cause)
    }
}
