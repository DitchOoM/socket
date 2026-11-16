@file:OptIn(com.ditchoom.socket.quic.InternalQuicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.NativeSocketRefusal
import com.ditchoom.socket.quic.NetworkRefusal
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicNativeMemoryRequiredException
import com.ditchoom.socket.quic.QuicStreamAbort
import com.ditchoom.socket.quic.QuicStreamClosedException
import com.ditchoom.socket.quic.QuicStreamException
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamReadError
import com.ditchoom.socket.quic.QuicStreamReadException
import com.ditchoom.socket.quic.QuicStreamSide
import com.ditchoom.socket.quic.QuicWriteTarget
import com.ditchoom.socket.quic.QuicheDriver
import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.UnresolvedRouteSourceException
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_INVALID_STREAM_STATE
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_STREAM_RESET
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_STREAM_STOPPED
import com.ditchoom.socket.quic.core.udp.SocketFault
import com.ditchoom.socket.quic.core.udp.SpecLayoutMismatch
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.time.Duration

/** Every failure a caller of the edge can receive. */
internal sealed interface OpFailure {
    class DeadlinePassed(
        val bound: Duration,
    ) : OpFailure
}

/** A failure that is an exception, raised on the caller's thread or resumed into its continuation. */
internal sealed interface Raised : OpFailure {
    class ConnEnded(
        val end: ConnEnd,
    ) : Raised

    /** quiche refused a write with [code]; a stopped or reset stream carries the peer's [peer] code. */
    class WriteRefused(
        val stream: QuicStreamId,
        val code: Int,
        val peer: QuicAppErrorCode,
    ) : Raised

    class ReadRefused(
        val stream: QuicStreamId,
        val code: Int,
    ) : Raised

    /** What one operation's own attempt threw; the stream table is left consistent. */
    class Thrown(
        val exception: Exception,
    ) : Raised

    /** A typed stream refused a frame, or a call on it. */
    class Typed(
        val stream: QuicStreamId,
        val failure: TypedFailure,
    ) : Raised

    /** The caller already closed this side of the stream. */
    class StreamClosed(
        val stream: QuicStreamId,
        val side: QuicStreamSide,
    ) : Raised

    /** A write was handed memory quiche cannot read in place. */
    class NotNative(
        val stream: QuicStreamId,
        val buffer: ReadBuffer,
    ) : Raised

    /** The connect's factory makes memory the connection cannot carry datagrams in. */
    class FactoryNotNative(
        val buffer: ReadBuffer,
    ) : Raised

    /** The route to the peer named no local address to bind. */
    class RouteUnresolved(
        val reason: RouteProbeFailure,
    ) : Raised

    /** The connect was given a set of loops that has been closed. */
    data object LoopsClosed : Raised

    /** The native stack could not open the socket or the loop's poller. */
    class SocketRefused(
        val refusal: NativeSocketRefusal,
    ) : Raised

    /** The loaded `qnet` lays its socket spec block out differently from this code. */
    class SpecLayoutRefused(
        val mismatch: SpecLayoutMismatch,
    ) : Raised

    /** Network.framework failed the client's connection before it was ready. */
    class NetworkRefused(
        val refusal: NetworkRefusal,
    ) : Raised
}

/** The one place the edge hands a caller a failure: [failure] resumes this continuation as an exception. */
internal fun <T> CancellableContinuation<T>.fail(
    failure: OpFailure,
    during: QuicCloseCircumstance,
) {
    if (!isActive) return
    when (failure) {
        is OpFailure.DeadlinePassed -> timeOut(failure.bound)
        is Raised -> resumeWithException(exceptionOf(failure, during))
    }
}

/** Raises [failure] on the caller's own thread, for a caller that is not a continuation. */
internal fun raiseFailure(
    failure: OpFailure,
    during: QuicCloseCircumstance,
): Nothing =
    when (failure) {
        is OpFailure.DeadlinePassed -> throw deadlineException(failure.bound)
        is Raised -> raise(failure, during)
    }

/** Raises, on the caller's thread, the cancellation of the job in [context], which a caller that was cancelled has. */
@OptIn(InternalCoroutinesApi::class)
internal fun raiseCancelled(context: CoroutineContext): Nothing = throw context.job.getCancellationException()

/** The exception a zero-length timeout throws, which is what a stream deadline raises everywhere else. */
private fun deadlineException(bound: Duration): Throwable {
    val caught = ArrayList<Throwable>(1)
    suspend { timedOut(bound) }.startCoroutine(Continuation(EmptyCoroutineContext) { it.onFailure { thrown -> caught.add(thrown) } })
    return caught[0]
}

/** Raises [failure] on the caller's own thread, before any work was handed to the connection. */
internal fun raise(
    failure: Raised,
    during: QuicCloseCircumstance,
): Nothing = throw exceptionOf(failure, during)

/** The exception that reports [end] to a caller who was [during] something, for channels that are closed with one. */
internal fun endedWith(
    end: ConnEnd,
    during: QuicCloseCircumstance,
): Throwable = closeOf(end, during)

/** How [end] reads in the public close reason. */
internal fun closeReasonOf(end: ConnEnd): QuicCloseReason =
    when (end) {
        is ConnEnd.ByPeer -> QuicCloseReason.ByPeer(end.error)
        is ConnEnd.ByLocal -> QuicCloseReason.ByLocal(end.error)
        is ConnEnd.IdleTimeout -> QuicCloseReason.ByLocal(QuicError.IdleTimeout)
        is ConnEnd.HandshakeTimedOut -> QuicCloseReason.ByLocal(QuicError.HandshakeTimeout(end.progress.waited))
        is ConnEnd.ProcessingFailed -> QuicCloseReason.ByLocal(QuicError.ProcessingFailed)
        is ConnEnd.ServerClosed -> QuicCloseReason.ByLocal(QuicError.NoError)
        is ConnEnd.ServerClosedUnsettled -> QuicCloseReason.ByLocal(QuicError.ServerStoppedBeforeCloseSent)
        is ConnEnd.NoUsablePath -> QuicCloseReason.ByLocal(QuicError.NoViablePath)
        is ConnEnd.SocketFailed, is ConnEnd.CallerFailed, is ConnEnd.LoopFailed, is ConnEnd.LoopStopped,
        is ConnEnd.NoErrorRecorded, is ConnEnd.Released, is ConnEnd.NetworkLost, is ConnEnd.LocalAddressGone,
        -> QuicCloseReason.Unspecified
    }

private fun exceptionOf(
    failure: Raised,
    during: QuicCloseCircumstance,
): Exception =
    when (failure) {
        is Raised.ConnEnded -> closeOf(failure.end, during)
        is Raised.WriteRefused ->
            when (failure.code) {
                QUICHE_ERR_STREAM_STOPPED -> QuicStreamException(failure.stream.id, QuicStreamAbort.StopSending(failure.peer))
                QUICHE_ERR_STREAM_RESET -> QuicStreamException(failure.stream.id, QuicStreamAbort.ResetStream(failure.peer))
                else -> QuicStreamWriteFailed(failure.stream.id, failure.code)
            }
        is Raised.ReadRefused ->
            QuicStreamReadException(
                failure.stream.id,
                if (failure.code == QUICHE_ERR_INVALID_STREAM_STATE) {
                    QuicStreamReadError.InvalidStreamState
                } else {
                    QuicStreamReadError.Quiche(failure.code)
                },
            )
        is Raised.Thrown -> failure.exception
        is Raised.StreamClosed -> QuicStreamClosedException(failure.stream.id, failure.side)
        is Raised.Typed -> QuicTypedStreamException(failure.stream.id, failure.failure)
        is Raised.NotNative ->
            QuicNativeMemoryRequiredException.forBuffer(
                QuicWriteTarget.Stream(failure.stream.id),
                failure.buffer,
                QuicheDriver.capabilities,
            )
        is Raised.FactoryNotNative ->
            QuicNativeMemoryRequiredException.forBuffer(QuicWriteTarget.Datagram, failure.buffer, QuicheDriver.capabilities)
        is Raised.RouteUnresolved -> UnresolvedRouteSourceException(failure.reason)
        is Raised.LoopsClosed -> CoreLoopsClosed()
        is Raised.SocketRefused -> failure.refusal
        is Raised.SpecLayoutRefused -> CoreSpecLayoutMismatch(failure.mismatch)
        is Raised.NetworkRefused -> failure.refusal
    }

/** The close exception for [end]: its reason, the circumstance it names, and its thrown object for the stack trace. */
private fun closeOf(
    end: ConnEnd,
    during: QuicCloseCircumstance,
): QuicCloseException =
    when (val trace = end.trace()) {
        is Trace.None -> QuicCloseException(end.reason(), end.circumstance(during))
        is Trace.Of -> QuicCloseException(end.reason(), end.circumstance(during), trace.thrown)
    }

/** Whether an end carries a thrown object, kept as the exception's cause for stack traces only. */
private sealed interface Trace {
    data object None : Trace

    class Of(
        val thrown: Throwable,
    ) : Trace
}

private fun ConnEnd.reason(): QuicCloseReason = closeReasonOf(this)

private fun ConnEnd.circumstance(during: QuicCloseCircumstance): QuicCloseCircumstance =
    when (this) {
        is ConnEnd.ProcessingFailed -> QuicCloseCircumstance.ProcessingFailed
        is ConnEnd.LoopFailed -> QuicCloseCircumstance.LoopThreadFailed
        is ConnEnd.CallerFailed -> QuicCloseCircumstance.CallerThreadFailed
        is ConnEnd.Released -> QuicCloseCircumstance.Released
        is ConnEnd.ByPeer, is ConnEnd.ByLocal, is ConnEnd.IdleTimeout, is ConnEnd.HandshakeTimedOut,
        is ConnEnd.SocketFailed, is ConnEnd.LoopStopped, is ConnEnd.ServerClosed, is ConnEnd.NoErrorRecorded,
        is ConnEnd.NetworkLost, is ConnEnd.LocalAddressGone, is ConnEnd.ServerClosedUnsettled, is ConnEnd.NoUsablePath,
        -> during
    }

private fun ConnEnd.trace(): Trace =
    when (this) {
        is ConnEnd.ProcessingFailed -> Trace.Of(thrown.throwable)
        is ConnEnd.CallerFailed -> Trace.Of(thrown.throwable)
        is ConnEnd.LoopFailed -> Trace.Of(thrown.throwable)
        is ConnEnd.SocketFailed ->
            when (val fault = fault) {
                is SocketFault.Unnamed -> Trace.Of(fault.thrown.throwable)
                is SocketFault.Repeated -> Trace.Of(fault.thrown.throwable)
                is SocketFault.ReopenRefused -> Trace.Of(fault.thrown.throwable)
                is SocketFault.MoveRefused -> Trace.Of(fault.thrown.throwable)
                is SocketFault.Closed -> Trace.None
            }
        is ConnEnd.ByPeer, is ConnEnd.ByLocal, is ConnEnd.IdleTimeout, is ConnEnd.HandshakeTimedOut,
        is ConnEnd.LoopStopped, is ConnEnd.ServerClosed, is ConnEnd.NoErrorRecorded, is ConnEnd.Released,
        is ConnEnd.NetworkLost, is ConnEnd.LocalAddressGone, is ConnEnd.ServerClosedUnsettled, is ConnEnd.NoUsablePath,
        -> Trace.None
    }

/** The same exception a 4.x stream deadline raises, built where it is thrown from, so callers see one type. */
private fun <T> CancellableContinuation<T>.timeOut(bound: Duration) {
    suspend { timedOut(bound) }.startCoroutine(Continuation(EmptyCoroutineContext) { it.onFailure(::resumeWithException) })
}

@Suppress("UNUSED_PARAMETER") // the bound is the caller's; the exception type is the contract
private suspend fun timedOut(bound: Duration): Nothing = withTimeout(Duration.ZERO) { awaitCancellation() }

/** A connect was given a set of loops that has been closed. */
internal class CoreLoopsClosed : IllegalStateException()

/** The native library this process loaded does not lay out the socket spec block as this code does. */
internal class CoreSpecLayoutMismatch(
    val mismatch: SpecLayoutMismatch,
) : IllegalStateException()

/** A write quiche refused for a reason of its own, by quiche's code. */
internal class QuicStreamWriteFailed(
    val streamId: Long,
    val quicheCode: Int,
) : Exception()

/** What a typed stream throws: [failure] says which rule the frame or the call broke. */
internal class QuicTypedStreamException(
    val streamId: Long,
    val failure: TypedFailure,
) : Exception()
