@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicAbortCodes
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicSendStream
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_STREAM_STOPPED
import kotlinx.coroutines.currentCoroutineContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Where a caller's sending on a public stream stands: a failure that ended it is what every later call answers. */
private sealed interface Verdict {
    data object Writing : Verdict

    data object Finished : Verdict

    class Failed(
        val why: StreamWrite.NotWritten,
    ) : Verdict
}

/** A [SendHalf] as the public [QuicSendStream]: every outcome of a write is a value, and a failure stays. */
@Published
internal class CoreSendStream(
    private val half: SendHalf,
    private val codes: QuicAbortCodes,
) : QuicSendStream {
    private val verdict = AtomicReference<Verdict>(Verdict.Writing)

    override val id: QuicStreamId get() = half.id
    override val writePolicy: WritePolicy get() = half.writePolicy

    override suspend fun write(
        buffer: ReadBuffer,
        policy: WritePolicy,
    ): StreamWrite {
        when (val now = verdict.load()) {
            is Verdict.Failed -> return now.why
            is Verdict.Writing, is Verdict.Finished -> Unit
        }
        val start = buffer.position()
        while (buffer.remaining() > 0) {
            when (val answer = half.writeSettled(buffer, deadlineOf(policy))) {
                is WriteAnswer.Written -> Unit
                is WriteAnswer.Failed -> return failed(answer, buffer.position() - start)
                is WriteAnswer.Cancelled -> raiseCancelled(currentCoroutineContext())
            }
        }
        return StreamWrite.Written
    }

    override suspend fun writeAndFinish(
        buffer: ReadBuffer,
        policy: WritePolicy,
    ): StreamWrite {
        when (val now = verdict.load()) {
            is Verdict.Failed -> return now.why
            is Verdict.Writing, is Verdict.Finished -> Unit
        }
        val start = buffer.position()
        return finished(half.finishSettled(buffer, deadlineOf(policy)), buffer.position() - start)
    }

    override suspend fun finish(): StreamWrite =
        when (val now = verdict.load()) {
            is Verdict.Failed -> now.why
            is Verdict.Finished -> StreamWrite.Written
            is Verdict.Writing -> finished(half.finSettled(), 0)
        }

    override fun reset(code: QuicAppErrorCode) = half.reset(code)

    /** The block that owned this stream returned: a direction still open is finished. */
    suspend fun complete() {
        if (verdict.load() is Verdict.Writing && half.isOpen()) finish()
    }

    private suspend fun finished(
        answer: WriteAnswer,
        taken: Int,
    ): StreamWrite =
        when (answer) {
            is WriteAnswer.Written -> {
                verdict.compareAndSet(Verdict.Writing, Verdict.Finished)
                StreamWrite.Written
            }
            is WriteAnswer.Failed -> failed(answer, taken)
            is WriteAnswer.Cancelled -> raiseCancelled(currentCoroutineContext())
        }

    /** The first failure ends the direction and stays; a write the deadline cut is reset, so the peer reads no part as the whole. */
    private fun failed(
        answer: WriteAnswer.Failed,
        taken: Int,
    ): StreamWrite.NotWritten {
        val why = notWritten(answer, taken)
        if (!verdict.compareAndSet(Verdict.Writing, Verdict.Failed(why))) {
            when (val first = verdict.load()) {
                is Verdict.Failed -> return first.why
                is Verdict.Writing, is Verdict.Finished -> Unit
            }
        }
        when (why) {
            is StreamWrite.NotWritten.DeadlinePassed -> half.reset(codes.writeCut)
            is StreamWrite.NotWritten.PeerStopped, is StreamWrite.NotWritten.ConnectionEnded -> Unit
        }
        return why
    }
}

/** What a failed write is to its caller: one of the public outcomes, or a misuse, which raises. */
private fun notWritten(
    answer: WriteAnswer.Failed,
    taken: Int,
): StreamWrite.NotWritten =
    when (val failure = answer.failure) {
        is OpFailure.DeadlinePassed -> StreamWrite.NotWritten.DeadlinePassed(taken.toLong())
        is Raised.ConnEnded -> StreamWrite.NotWritten.ConnectionEnded(publicEndOf(failure.end))
        is Raised.PastClose -> StreamWrite.NotWritten.ConnectionEnded(publicEndOf(failure.end))
        is Raised.WriteRefused ->
            when (failure.code) {
                QUICHE_ERR_STREAM_STOPPED -> StreamWrite.NotWritten.PeerStopped(failure.peer)
                else -> raise(failure, answer.during)
            }
        is Raised.ReadRefused, is Raised.Thrown, is Raised.Typed, is Raised.StreamClosed, is Raised.NoSuchDirection,
        is Raised.NotNative, is Raised.FactoryNotNative, is Raised.RouteUnresolved, is Raised.LoopsClosed,
        is Raised.StreamsHandled, is Raised.SocketRefused, is Raised.SpecLayoutRefused, is Raised.NetworkRefused,
        is Raised.AccessDenied, is Raised.ChainRefused,
        -> raise(failure, answer.during)
    }
