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

/**
 * Run [block] on this connection for no longer than the connection lives — the [QuicScope] contract
 * that a block is cancelled when its connection dies. Every scope runner ([withQuicConnection],
 * [QuicServer.connections]) runs user code through this, and it is public only so a runner in an engine
 * module can.
 *
 * Returns what [block] returns when it finishes first. When the connection dies first — the peer closed
 * it, it idled out, the transport failed — [block] is cancelled, and once it has unwound this throws a
 * [QuicCloseException] carrying the close's [QuicCloseReason]. Anything [block] throws itself propagates
 * unchanged, and a cancellation of the caller stays a cancellation.
 *
 * A close this application made itself ([QuicScope.closeWithError]) is not the connection dying: the
 * block that asked for it is not cancelled, and unwinds on its own with whatever it has to report.
 */
suspend fun <R> QuicConnection.runUntilClosed(block: suspend QuicConnection.() -> R): R {
    val diedFirst = CompletableDeferred<QuicCloseException>()
    return try {
        coroutineScope {
            val body = async(start = CoroutineStart.UNDISPATCHED) { block() }
            val watch =
                launch {
                    val closed = state.filterIsInstance<QuicConnectionState.Closed>().first()
                    if (closed.reason.isThisApplicationsClose()) return@launch
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
