package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Tier
import com.ditchoom.socket.quic.core.life.Tiers
import com.ditchoom.socket.quic.core.sched.HandlerLane
import com.ditchoom.socket.quic.core.sched.HandlerObservation
import com.ditchoom.socket.quic.core.sched.HandlerSite
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.reportOnThisThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlin.jvm.JvmInline

/** What [handleStreams] answered. */
internal sealed interface StreamsHandled {
    /** The connection ended with [end], and every handler it started has returned. */
    class Ended(
        val end: ConnEnd,
    ) : StreamsHandled

    /** Another call handles this connection's streams: a connection's streams are handled by one call. */
    data object AlreadyHandling : StreamsHandled
}

/** The application error code a stream is reset with when its handler fails: the application's code space, so its choice. */
@JvmInline
internal value class HandlerResetCode(
    val code: QuicAppErrorCode,
) {
    companion object {
        /** The default, `0x2`. */
        val Default = HandlerResetCode(QuicAppErrorCode(0x2L))
    }
}

/**
 * Runs [handler] for every stream the peer opens on this connection, on the threads of its endpoint's pool, one step of
 * this connection's handlers at a time, and returns once the connection has ended and every handler has returned.
 *
 * - A new stream's handler coroutine is made on a pool thread: the loop only resumes the acceptor with the stream.
 * - A handler that fails (throws while its connection is open and it is not cancelled, a `CancellationException` of its
 *   own, such as a timeout's, included) is reported as [HandlerObservation.Failed] and then its stream is reset with
 *   [resetCode]; an `Error` is also told to the thread's uncaught handler, as the process tier is. Other streams and the
 *   connection go on.
 * - Nothing cancels a handler: once the connection ends its stream calls stop waiting, and each handler runs to its
 *   own return, after the endpoint's close has returned too if it is that long.
 * - The caller resumes from a pool thread, or from the default dispatcher once the endpoint has stopped: never from a
 *   loop.
 */
internal suspend fun CoreConnection.handleStreams(
    resetCode: HandlerResetCode = HandlerResetCode.Default,
    handler: suspend (QuicByteStream) -> Unit,
): StreamsHandled = handleStreams(resetCode, halves::public, handler)

/** [handleStreams] with each stream handed to [handler] as [shape] makes it. */
internal suspend fun <S> CoreConnection.handleStreams(
    resetCode: HandlerResetCode,
    shape: (IncomingStream) -> S,
    handler: suspend (S) -> Unit,
): StreamsHandled =
    when (val claimed = handlers.claim()) {
        is HandlerClaim.Taken -> StreamsHandled.AlreadyHandling
        is HandlerClaim.Refused -> withContext(Dispatchers.Default) { StreamsHandled.Ended(edge.closed.await().end) }
        is HandlerClaim.Claimed ->
            try {
                withContext(claimed.lane + HandlerSite.Accepting) {
                    supervisorScope { while (acceptOne(this, claimed.lane, resetCode, shape, handler) == Accepting.More) Unit }
                    StreamsHandled.Ended(edge.closed.await().end)
                }
            } finally {
                handlers.left()
            }
    }

/** Whether the acceptor takes another stream. */
private enum class Accepting { More, Ended }

/**
 * Takes the next stream the peer opens and starts its handler. Only the connection's end ends the accepting: its close
 * fails the receive. Anything else that fails it is reported as [HandlerObservation.AcceptFailed], and the next is taken.
 */
private suspend inline fun <S> CoreConnection.acceptOne(
    scope: CoroutineScope,
    lane: HandlerLane,
    resetCode: HandlerResetCode,
    shape: (IncomingStream) -> S,
    noinline handler: suspend (S) -> Unit,
): Accepting {
    var next = Accepting.More
    handlerCode(failed = { failure -> next = acceptFailed(scope, lane, failure) }) {
        // A plain receive resumes with the stream itself, where a catching one would box its result per stream.
        val incoming = shaped(edge.incoming.receiveAny())
        val stream = shape(incoming)
        // Started at once, on this thread and this step: the handler's coroutine is made here, on the pool.
        scope.launch(HandlerSite.Stream(incoming.id), CoroutineStart.UNDISPATCHED) {
            serve(this, lane, incoming, stream, resetCode, handler)
        }
    }
    return next
}

private fun CoreConnection.acceptFailed(
    scope: CoroutineScope,
    lane: HandlerLane,
    failure: Throwable,
): Accepting {
    if (failure is QuicCloseException || !scope.isActive || edge.end() is EdgeEnd.Ended) return Accepting.Ended
    handlers.report(HandlerObservation.AcceptFailed(lane.connection, Thrown(failure)))
    processTier(failure)
    return Accepting.More
}

private suspend inline fun <S> CoreConnection.serve(
    scope: CoroutineScope,
    lane: HandlerLane,
    incoming: IncomingStream,
    stream: S,
    resetCode: HandlerResetCode,
    handler: suspend (S) -> Unit,
) {
    handlerCode(failed = { failure -> handlerFailed(scope, lane, incoming, resetCode, failure) }) { handler(stream) }
}

/** A handler ended by its connection's close, or cancelled, has not failed; one that failed is reported, then its stream. */
private fun CoreConnection.handlerFailed(
    scope: CoroutineScope,
    lane: HandlerLane,
    stream: IncomingStream,
    resetCode: HandlerResetCode,
    failure: Throwable,
) {
    if (failure is QuicCloseException || !scope.isActive) return
    handlers.report(HandlerObservation.Failed(lane.connection, HandlerSite.Stream(stream.id), Thrown(failure)))
    failStream(stream, resetCode)
    processTier(failure)
}

/** An `Error` reaches the thread's uncaught handler, as the process tier says; an exception goes no further. */
private fun processTier(failure: Throwable) {
    when (Tiers.of(failure)) {
        Tier.Connection -> Unit
        Tier.Process -> reportOnThisThread(failure)
    }
}

/** The stream [peer] is, shaped by its direction: both halves, or the receiving half alone. */
private fun CoreConnection.shaped(peer: PeerStream): IncomingStream = halves.incomingOf(peer)

/** Fails [stream] for its peer with [code]: STOP_SENDING for the peer's direction, and RESET_STREAM for ours if it has one. */
private fun failStream(
    stream: IncomingStream,
    code: HandlerResetCode,
) {
    when (stream) {
        is IncomingStream.Bidirectional -> {
            stream.stream.receive.stop(code.code)
            stream.stream.send.reset(code.code)
        }
        is IncomingStream.ReceiveOnly -> stream.receive.stop(code.code)
    }
}
