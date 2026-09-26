package com.ditchoom.socket.quic

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Whether the peer has acknowledged everything a connection wrote on its streams — every byte and every
 * FIN; a send side the peer stopped, or this endpoint reset, owes nothing. See [QuicheDriver.sentStreamData].
 */
internal sealed interface SentStreamData {
    /** Something written is not yet known to be acknowledged — including before it has first been measured. */
    data object AwaitingAcknowledgement : SentStreamData

    /** The peer has acknowledged all of it. */
    data object Acknowledged : SentStreamData
}

/**
 * Hold this connection's CONNECTION_CLOSE after its handler returns, as [closeLinger] says — the
 * graceful-close half of a server's lifetime.
 *
 * A handler's last write is only *handed to quiche*; whether it reached the peer is decided on the wire
 * afterwards. RFC 9000 §10.2 lets an endpoint that has sent CONNECTION_CLOSE send nothing else, so bytes
 * still unacknowledged at the close are never retransmitted and the peer ends up with part of the reply.
 * Lingering keeps the connection running — loss timers armed, ACKs processed, data retransmitted — until
 * it is safe to close:
 *
 *  - [QuicCloseLinger.UntilAcknowledged] until the peer has acknowledged everything written on the
 *    connection's streams ([QuicheDriver.sentStreamData]) or the connection ends on its own, bounded by
 *    [idleTimeout];
 *  - [QuicCloseLinger.UntilPeerDone] until the connection ends on its own (the peer's CONNECTION_CLOSE,
 *    or an idle timeout), bounded by its fixed [QuicCloseLinger.UntilPeerDone.bound].
 *
 * Cancellation (the server closing, which destroys the driver) ends the wait at once.
 */
internal suspend fun QuicheDriver.lingerBeforeClose(
    closeLinger: QuicCloseLinger,
    idleTimeout: Duration,
) {
    when (closeLinger) {
        QuicCloseLinger.Immediate -> Unit
        is QuicCloseLinger.UntilPeerDone ->
            withTimeoutOrNull(closeLinger.bound) { state.first { it is QuicConnectionState.Closed } }
        QuicCloseLinger.UntilAcknowledged -> withTimeoutOrNull(idleTimeout) { untilDeliveredOrClosed() }
    }
}

/**
 * Suspends until [QuicheDriver.sentStreamData] reads [SentStreamData.Acknowledged] or the connection
 * reaches [QuicConnectionState.Closed]. The first measurement is asked for once the wait is subscribed,
 * so an idle connection whose data was acknowledged before the wait began still ends it at once.
 */
private suspend fun QuicheDriver.untilDeliveredOrClosed(): Unit =
    coroutineScope {
        val closed = async { state.first { it is QuicConnectionState.Closed } }
        val delivered =
            async {
                sentStreamData
                    .onSubscription { inspect { _, _ -> } }
                    .first { it is SentStreamData.Acknowledged }
            }
        select {
            closed.onAwait { }
            delivered.onAwait { }
        }
        coroutineContext.cancelChildren()
    }
