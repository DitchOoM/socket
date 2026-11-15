package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.DeadlineNode
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicStreamAbort
import com.ditchoom.socket.quic.QuicStreamException
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamReadError
import com.ditchoom.socket.quic.QuicStreamReadException
import com.ditchoom.socket.quic.StreamRecvResult
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.WriteEnd
import com.ditchoom.socket.quic.core.stream.Parked
import com.ditchoom.socket.quic.core.stream.Received
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.StreamEntry
import com.ditchoom.socket.quic.core.stream.StreamMap
import com.ditchoom.socket.quic.core.stream.Waiter
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Runnable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/** How a stream operation ended, as its caller learns it. */
internal sealed interface Outcome<out T> {
    class Done<T>(
        val value: T,
    ) : Outcome<T>

    object TimedOut : Outcome<Nothing>
}

private sealed interface DeadlineBound {
    object None : DeadlineBound

    class Armed(
        val node: DeadlineNode,
    ) : DeadlineBound
}

/**
 * One read or write: first run as work inside the connection, then parked on its stream until the stream can
 * serve it, its deadline passes, its caller is cancelled or the connection ends.
 */
internal abstract class StreamOp<T>(
    protected val streams: StreamMap,
    protected val stream: QuicStreamId,
    private val deadline: Duration,
    private val cont: CancellableContinuation<Outcome<T>>,
    private val submit: (ConnWork) -> Unit,
) : ConnWork(),
    Parked {
    private var bound: DeadlineBound = DeadlineBound.None

    /** Tries once, inside the connection. */
    protected abstract fun attempt(
        inside: Conn.Inside,
        entry: StreamEntry,
    ): Attempt<T>

    protected abstract fun park(
        entry: StreamEntry,
        waiter: Waiter,
    )

    protected abstract fun parked(entry: StreamEntry): Waiter

    /** Frees what a value holds when the caller is gone before it receives it. */
    protected open fun discard(value: T) = Unit

    override fun run(inside: Conn.Inside) {
        val entry = streams.entry(stream)
        if (!cont.isActive) return
        when (val first = attempt(inside, entry)) {
            is Attempt.Finished -> finish(first)
            Attempt.Blocked -> {
                park(entry, Waiter.Waiting(this))
                if (deadline.isFinite()) {
                    bound =
                        DeadlineBound.Armed(inside.deadline(deadline.inWholeNanoseconds, Runnable { submit(Expire(this)) }))
                }
            }
        }
    }

    override fun retry(
        inside: Conn.Inside,
        stream: StreamEntry,
    ): Retry {
        if (!cont.isActive) {
            disarm()
            return Retry.Settled
        }
        return when (val again = attempt(inside, stream)) {
            is Attempt.Finished -> {
                disarm()
                finish(again)
                Retry.Settled
            }
            Attempt.Blocked -> Retry.Again
        }
    }

    override fun connectionEnded(failure: Throwable) {
        disarm()
        if (cont.isActive) cont.resumeWithException(failure)
    }

    override fun gone() {
        if (cont.isActive) cont.resumeWithException(ConnectionReleased.failure())
    }

    /** Inside the connection: the deadline passed while parked. */
    internal fun expire(inside: Conn.Inside) {
        val entry = streams.entry(stream)
        val now = parked(entry)
        if (now is Waiter.Waiting && now.op === this) {
            park(entry, Waiter.Nobody)
            if (cont.isActive) cont.resume(Outcome.TimedOut)
        }
    }

    /** Inside the connection: the caller was cancelled while parked. */
    internal fun withdraw() {
        val entry = streams.entry(stream)
        val now = parked(entry)
        if (now is Waiter.Waiting && now.op === this) park(entry, Waiter.Nobody)
        disarm()
    }

    private fun finish(done: Attempt.Finished<T>) {
        when (val result = done.result) {
            is Result.Value -> {
                val value = result.value
                cont.resume(Outcome.Done(value)) { _, _, _ -> discard(value) }
            }
            is Result.Failure -> cont.resumeWithException(result.error)
        }
    }

    private fun disarm() {
        when (val armed = bound) {
            DeadlineBound.None -> Unit
            is DeadlineBound.Armed -> armed.node.disarm()
        }
        bound = DeadlineBound.None
    }
}

internal sealed interface Result<out T> {
    class Value<T>(
        val value: T,
    ) : Result<T>

    class Failure(
        val error: Throwable,
    ) : Result<Nothing>
}

internal sealed interface Attempt<out T> {
    class Finished<T>(
        val result: Result<T>,
    ) : Attempt<T>

    object Blocked : Attempt<Nothing>
}

private class Expire(
    private val op: StreamOp<*>,
) : ConnWork() {
    override fun run(inside: Conn.Inside) = op.expire(inside)

    override fun gone() = Unit
}

internal class Withdraw(
    private val op: StreamOp<*>,
) : ConnWork() {
    override fun run(inside: Conn.Inside) = op.withdraw()

    override fun gone() = Unit
}

/** Reads the next chunk into a buffer from the connection's factory; the caller owns a returned buffer. */
internal class ReadOp(
    streams: StreamMap,
    stream: QuicStreamId,
    deadline: Duration,
    cont: CancellableContinuation<Outcome<ReadResult>>,
    submit: (ConnWork) -> Unit,
    private val factory: BufferFactory,
    private val chunk: Int,
) : StreamOp<ReadResult>(streams, stream, deadline, cont, submit) {
    override fun attempt(
        inside: Conn.Inside,
        entry: StreamEntry,
    ): Attempt<ReadResult> {
        if (entry.received === Received.Finished) return done(ReadResult.End)
        val buffer = factory.allocate(chunk)
        return when (val got = inside.streamRecv(stream, addressOf(buffer), chunk)) {
            is StreamRecvResult.Data -> {
                if (got.fin) entry.received = Received.Finished
                if (got.bytesRead == 0) {
                    buffer.freeNativeMemory()
                    if (got.fin) done(ReadResult.End) else Attempt.Blocked
                } else {
                    buffer.position(0)
                    buffer.setLimit(got.bytesRead)
                    done(ReadResult.Data(buffer))
                }
            }
            StreamRecvResult.Done -> {
                buffer.freeNativeMemory()
                Attempt.Blocked
            }
            is StreamRecvResult.Reset -> {
                buffer.freeNativeMemory()
                done(ReadResult.Reset)
            }
            is StreamRecvResult.Error -> {
                buffer.freeNativeMemory()
                Attempt.Finished(Result.Failure(StreamFailure.ofRead(stream, got.code)))
            }
            StreamRecvResult.ConnectionGone -> {
                buffer.freeNativeMemory()
                Attempt.Finished(Result.Failure(ConnectionReleased.failure()))
            }
        }
    }

    override fun park(
        entry: StreamEntry,
        waiter: Waiter,
    ) {
        entry.reader = waiter
    }

    override fun parked(entry: StreamEntry): Waiter = entry.reader

    override fun discard(value: ReadResult) {
        if (value is ReadResult.Data) (value.buffer as PlatformBuffer).freeNativeMemory()
    }

    private fun done(result: ReadResult): Attempt<ReadResult> = Attempt.Finished(Result.Value(result))
}

/** Writes what quiche takes now of [buffer]'s remaining bytes, at least one; moves its position past them. */
internal class WriteOp(
    streams: StreamMap,
    stream: QuicStreamId,
    deadline: Duration,
    cont: CancellableContinuation<Outcome<Int>>,
    submit: (ConnWork) -> Unit,
    private val buffer: ReadBuffer,
    private val address: Long,
    private val end: WriteEnd,
) : StreamOp<Int>(streams, stream, deadline, cont, submit) {
    override fun attempt(
        inside: Conn.Inside,
        entry: StreamEntry,
    ): Attempt<Int> {
        val start = buffer.position()
        val length = buffer.remaining()
        val sent = inside.streamSend(stream, address + start, length, end)
        return when {
            sent.result > 0 || (sent.result == 0 && length == 0) -> {
                buffer.position(start + sent.result)
                Attempt.Finished(Result.Value(sent.result))
            }
            sent.result == 0 || sent.result == QUICHE_ERR_DONE -> {
                inside.wantWritable(stream)
                Attempt.Blocked
            }
            else -> Attempt.Finished(Result.Failure(StreamFailure.ofSend(stream, sent.result, sent.errorCode ?: StreamFailure.UNKNOWN)))
        }
    }

    override fun park(
        entry: StreamEntry,
        waiter: Waiter,
    ) {
        entry.writer = waiter
    }

    override fun parked(entry: StreamEntry): Waiter = entry.writer
}

/** quiche's own error codes stay quiche's: they are never passed off as the peer's application error codes. */
internal object StreamFailure {
    fun ofRead(
        stream: QuicStreamId,
        code: Int,
    ): Throwable =
        QuicStreamReadException(
            streamId = stream.id,
            error =
                if (code ==
                    QUICHE_ERR_INVALID_STREAM_STATE
                ) {
                    QuicStreamReadError.InvalidStreamState
                } else {
                    QuicStreamReadError.Quiche(code)
                },
            message = "QUIC stream ${stream.id} read failed (quiche code $code)",
        )

    private fun ofWrite(
        stream: QuicStreamId,
        code: Int,
    ): Throwable = QuicStreamWriteFailed(stream.id, code)

    fun ofSend(
        stream: QuicStreamId,
        code: Int,
        peer: QuicAppErrorCode,
    ): Throwable =
        when (code) {
            QUICHE_ERR_STREAM_STOPPED -> QuicStreamException(stream.id, QuicStreamAbort.StopSending(peer), "peer stopped the stream")
            QUICHE_ERR_STREAM_RESET -> QuicStreamException(stream.id, QuicStreamAbort.ResetStream(peer), "peer reset the stream")
            else -> ofWrite(stream, code)
        }

    /** The peer's code when the backend does not report it. */
    val UNKNOWN = QuicAppErrorCode(0L)
}

internal fun addressOf(buffer: ReadBuffer): Long = buffer.nativeMemoryAccess!!.nativeAddress.toLong()

/** A write quiche refused for a reason of its own, by quiche's code. */
internal class QuicStreamWriteFailed(
    val streamId: Long,
    val quicheCode: Int,
) : IllegalStateException()

private const val QUICHE_ERR_DONE = -1
private const val QUICHE_ERR_INVALID_STREAM_STATE = -7
private const val QUICHE_ERR_STREAM_STOPPED = -15
private const val QUICHE_ERR_STREAM_RESET = -16
