package com.ditchoom.socket.quic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * What a connection still holds for its application once it has closed: bytes it received on a stream,
 * or a stream's FIN or RESET, that no read has returned to its caller yet — including bytes already handed
 * to a read that has not returned.
 */
sealed interface QuicUnreadAtClose {
    /** The connection has not closed; what it receives is still arriving. */
    data object ConnectionOpen : QuicUnreadAtClose

    /** Closed, and every stream's received bytes and terminal FIN or RESET have been read or released. */
    data object AllRead : QuicUnreadAtClose

    /**
     * Closed, and each of [streams] still holds received bytes (queued, or handed to a read that has not
     * returned), a FIN or a RESET not yet read.
     */
    data class Unread(
        val streams: Set<QuicStreamId>,
    ) : QuicUnreadAtClose {
        /** This with [stream] read to its end: [AllRead] once no stream is left. */
        fun without(stream: QuicStreamId): QuicUnreadAtClose =
            (streams - stream).let { left -> if (left.isEmpty()) AllRead else Unread(left) }
    }
}

/**
 * Run [block] on this connection for no longer than the connection lives — the [QuicScope] contract
 * that a block is cancelled when its connection dies. Every scope runner ([withQuicConnection],
 * [QuicServer.connections]) runs user code through this, and it is public only so a runner in an engine
 * module can.
 *
 * Returns what [block] returns when it finishes first. When the connection dies first — the peer closed
 * it, it idled out, the transport failed — [block] is cancelled once nothing the connection received is
 * left to read ([QuicConnection.unreadAtClose] reaching [QuicUnreadAtClose.AllRead]), so a reply that
 * landed in the same flight as the peer's close is still delivered; and once it has unwound this throws
 * a [QuicCloseException] carrying the close's [QuicCloseReason]. Anything [block] throws itself
 * propagates unchanged, and a cancellation of the caller stays a cancellation.
 *
 * [linger] bounds how long unread data keeps a block on a dead connection running; runners pass the
 * connection's idle timeout, the bound after which the same block with no traffic would have been
 * cancelled anyway.
 *
 * A close this application made itself ([QuicScope.closeWithError]) is not the connection dying: the
 * block that asked for it is not cancelled, and unwinds on its own with whatever it has to report.
 */
suspend fun <R> QuicConnection.runUntilClosed(
    linger: Duration,
    block: suspend QuicConnection.() -> R,
): R {
    val diedFirst = CompletableDeferred<QuicCloseException>()
    return try {
        coroutineScope {
            val body = async(start = CoroutineStart.UNDISPATCHED) { block() }
            val watch =
                launch {
                    val closed = state.filterIsInstance<QuicConnectionState.Closed>().first()
                    if (closed.reason.isThisApplicationsClose()) return@launch
                    withTimeoutOrNull(linger) { unreadAtClose.filterIsInstance<QuicUnreadAtClose.AllRead>().first() }
                    val close =
                        QuicCloseException(
                            closed.reason,
                            "connection closed while its block was running",
                            attribution = QuicCloseAttribution.Attributed(identity, networkAtClose),
                        )
                    diedFirst.complete(close)
                    body.cancel(CancellationException("connection closed", close))
                }
            try {
                body.await()
            } finally {
                watch.cancel()
            }
        }
    } catch (e: CancellationException) {
        // The caller's own cancellation wins over the connection's close.
        currentCoroutineContext().ensureActive()
        if (diedFirst.isCompleted) throw diedFirst.await()
        throw e
    }
}

/** An application-coded close sent by this endpoint: only [QuicScope.closeWithError] produces one. */
private fun QuicCloseReason.isThisApplicationsClose(): Boolean =
    when (this) {
        is QuicCloseReason.ByLocal -> error is QuicError.ApplicationError
        is QuicCloseReason.ByPeer, QuicCloseReason.Graceful, QuicCloseReason.Unspecified -> false
    }
