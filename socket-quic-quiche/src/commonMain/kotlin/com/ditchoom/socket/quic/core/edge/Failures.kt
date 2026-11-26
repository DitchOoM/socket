@file:OptIn(com.ditchoom.socket.quic.InternalQuicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.ServerCertificateRejectedException
import com.ditchoom.socket.ServerCertificateRejection
import com.ditchoom.socket.quic.NativeSocketRefusal
import com.ditchoom.socket.quic.NetworkRefusal
import com.ditchoom.socket.quic.QuicAccessDenial
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicNativeMemoryRequiredException
import com.ditchoom.socket.quic.QuicRefusedException
import com.ditchoom.socket.quic.QuicStreamAbort
import com.ditchoom.socket.quic.QuicStreamClosedException
import com.ditchoom.socket.quic.QuicStreamException
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamReadError
import com.ditchoom.socket.quic.QuicStreamReadException
import com.ditchoom.socket.quic.QuicStreamSide
import com.ditchoom.socket.quic.QuicStreamsAlreadyHandledException
import com.ditchoom.socket.quic.QuicWriteTarget
import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.UnresolvedRouteSourceException
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_INVALID_STREAM_STATE
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_STREAM_RESET
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_STREAM_STOPPED
import com.ditchoom.socket.quic.core.udp.SpecLayoutMismatch
import com.ditchoom.socket.quic.quicheCapabilities
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

    /** A write, an open or a datagram came after the close point of a close that ends with [end]; a typed frame cut short resets its stream. */
    class PastClose(
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

    /** A read came while another read of the stream was still out: a stream has one reader at a time. */
    class ReadInProgress(
        val stream: QuicStreamId,
    ) : Raised

    /** A call on a direction a unidirectional stream does not have: a read of a send-only one, a write of a receive-only one. */
    class NoSuchDirection(
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

    /** A second call asked to handle a connection's streams while a first still does. */
    data object StreamsHandled : Raised

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

    /** The system refused the client's socket the network access it needs, before its connection was built. */
    class AccessDenied(
        val denial: QuicAccessDenial,
    ) : Raised

    /** The server's chain was not kept whole for its check, which therefore fails as [rejection]. */
    class ChainRefused(
        val rejection: ServerCertificateRejection,
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

/** Raises an option refusal on the caller's thread, before anything is opened; a honoured verdict raises nothing. */
internal fun OptionVerdict.orRefuse() {
    when (this) {
        is OptionVerdict.Honoured -> Unit
        is OptionVerdict.Refused -> throw QuicRefusedException(refusal)
    }
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

/** Raises the close of a connection that ended with the public [end], to a caller who was [during] something. */
internal fun raiseEnded(
    end: QuicConnectionEnd,
    during: QuicCloseCircumstance,
): Nothing = throw QuicCloseException(end = end, circumstance = during)

private fun exceptionOf(
    failure: Raised,
    during: QuicCloseCircumstance,
): Exception =
    when (failure) {
        is Raised.ConnEnded -> closeOf(failure.end, during)
        is Raised.PastClose -> QuicCloseException(end = publicEndOf(failure.end), circumstance = QuicCloseCircumstance.Ended)
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
        is Raised.ReadInProgress -> QuicStreamReadException(failure.stream.id, QuicStreamReadError.ReadInProgress)
        is Raised.NoSuchDirection -> QuicStreamDirectionAbsent(failure.stream.id, failure.side)
        is Raised.Typed -> QuicTypedStreamException(failure.stream.id, failure.failure)
        is Raised.NotNative ->
            QuicNativeMemoryRequiredException.forBuffer(
                QuicWriteTarget.Stream(failure.stream.id),
                failure.buffer,
                quicheCapabilities,
            )
        is Raised.FactoryNotNative ->
            QuicNativeMemoryRequiredException.forBuffer(QuicWriteTarget.Datagram, failure.buffer, quicheCapabilities)
        is Raised.RouteUnresolved -> UnresolvedRouteSourceException(failure.reason)
        is Raised.ChainRefused -> ServerCertificateRejectedException(failure.rejection)
        is Raised.LoopsClosed -> CoreLoopsClosed()
        is Raised.StreamsHandled -> QuicStreamsAlreadyHandledException()
        is Raised.SocketRefused -> failure.refusal
        is Raised.SpecLayoutRefused -> CoreSpecLayoutMismatch(failure.mismatch)
        is Raised.NetworkRefused -> failure.refusal
        is Raised.AccessDenied ->
            QuicCloseException(
                end = QuicConnectionEnd.AccessDenied(failure.denial),
                circumstance = QuicCloseCircumstance.HandshakeFailed,
            )
    }

/** The close exception for [end]: its public end and the circumstance it names. */
internal fun closeOf(
    end: ConnEnd,
    during: QuicCloseCircumstance,
): QuicCloseException = QuicCloseException(end = publicEndOf(end), circumstance = end.circumstance(during))

/** The same exception a 4.x stream deadline raises, built where it is thrown from, so callers see one type. */
private fun <T> CancellableContinuation<T>.timeOut(bound: Duration) {
    suspend { timedOut(bound) }.startCoroutine(Continuation(EmptyCoroutineContext) { it.onFailure(::resumeWithException) })
}

@Suppress("UNUSED_PARAMETER") // the bound is the caller's; the exception type is the contract
private suspend fun timedOut(bound: Duration): Nothing = withTimeout(Duration.ZERO) { awaitCancellation() }

/** A connect was given a set of loops that has been closed. */
internal class CoreLoopsClosed : IllegalStateException()

/** The platform refused the thread a loop needs, with [cause]: nothing was opened, and the call may be made again. */
internal class CoreLoopThreadRefused(
    cause: Throwable,
) : IllegalStateException(cause)

/** The native library this process loaded does not lay out the socket spec block as this code does. */
internal class CoreSpecLayoutMismatch(
    val mismatch: SpecLayoutMismatch,
) : IllegalStateException()

/** A call on the [side] of a unidirectional stream that it does not have; distinct from a side the caller closed. */
internal class QuicStreamDirectionAbsent(
    val streamId: Long,
    val side: QuicStreamSide,
) : UnsupportedOperationException()

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
