package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.DeadlineNode
import com.ditchoom.socket.quic.DeadlineQueue
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicStreamAbort
import com.ditchoom.socket.quic.QuicStreamException
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamReadError
import com.ditchoom.socket.quic.QuicStreamReadException
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.WriteEnd
import com.ditchoom.socket.quic.core.stream.Nobody
import com.ditchoom.socket.quic.core.stream.Parked
import com.ditchoom.socket.quic.core.stream.Received
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.StreamEntry
import com.ditchoom.socket.quic.core.stream.StreamMap
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.time.Duration

/** What a deadline fires on: an operation that asks to be expired. */
internal interface Expiring {
    fun requestExpire()
}

private object Detached : Expiring {
    override fun requestExpire() = Unit
}

/** A deadline on one parked operation: the node is its own action, so a bound costs this one object. */
private class ExpireNode(
    queue: DeadlineQueue,
    dueNanos: Long,
    private var target: Expiring,
) : DeadlineNode(queue, dueNanos),
    DeadlineBound {
    override fun fire() = target.requestExpire()

    // The operation, and the continuation behind it, are dropped when the deadline is disarmed, not when it would have fallen due.
    override fun release() {
        target = Detached
    }
}

private sealed interface DeadlineBound {
    object None : DeadlineBound
}

/** What one attempt at a stream operation came to. */
internal sealed interface Attempt {
    /** Done: the operation holds what its caller gets, and [StreamOp.deliver] hands it over. */
    object Finished : Attempt

    object Blocked : Attempt

    class Failed(
        val error: Throwable,
    ) : Attempt
}

/**
 * One read or write: first run as work inside the connection, then parked on its stream until the stream can
 * serve it, its deadline passes, its caller is cancelled or the connection ends. It resumes its caller with the value
 * itself; a deadline or a failure resumes it with an exception.
 */
internal abstract class StreamOp<T>(
    protected val streams: StreamMap,
    protected val stream: QuicStreamId,
    private val deadline: Duration,
    protected val cont: CancellableContinuation<T>,
    private val submit: (ConnWork) -> Unit,
) : ConnWork(),
    Parked,
    Expiring {
    private var bound: DeadlineBound = DeadlineBound.None

    /** Tries once, inside the connection. */
    protected abstract fun attempt(
        inside: Conn.Inside,
        entry: StreamEntry,
    ): Attempt

    /** Resumes the caller with what a [Attempt.Finished] attempt left in this operation. */
    protected abstract fun deliver()

    protected abstract fun park(
        entry: StreamEntry,
        waiter: Parked,
    )

    protected abstract fun parked(entry: StreamEntry): Parked

    /** Frees what the operation still holds when it ends without delivering. */
    protected open fun release() = Unit

    override fun run(inside: Conn.Inside) = contained { start(inside) }

    private fun start(inside: Conn.Inside) {
        val entry = streams.entry(stream)
        if (!cont.isActive) return release()
        when (val first = attempt(inside, entry)) {
            Attempt.Finished -> deliver()
            Attempt.Blocked -> {
                park(entry, this)
                if (deadline.isFinite()) {
                    val node = ExpireNode(inside.deadlines, inside.dueAfter(deadline.inWholeNanoseconds), this)
                    bound = node
                    inside.arm(node)
                }
            }
            is Attempt.Failed -> fail(first.error)
        }
    }

    override fun retry(
        inside: Conn.Inside,
        stream: StreamEntry,
    ): Retry {
        try {
            return again(inside, stream)
        } catch (failure: Exception) {
            fail(failure)
            return Retry.Settled
        }
    }

    private fun again(
        inside: Conn.Inside,
        stream: StreamEntry,
    ): Retry {
        if (!cont.isActive) {
            disarm()
            release()
            return Retry.Settled
        }
        when (val again = attempt(inside, stream)) {
            Attempt.Finished -> {
                disarm()
                deliver()
            }
            Attempt.Blocked -> return Retry.Again
            is Attempt.Failed -> fail(again.error)
        }
        return Retry.Settled
    }

    override fun connectionEnded(failure: Throwable) {
        disarm()
        release()
        if (cont.isActive) cont.resumeWithException(failure)
    }

    override fun gone() {
        release()
        if (cont.isActive) cont.resumeWithException(ConnectionReleased.failure())
    }

    /** On the deadline's thread: asks the connection to expire this operation. */
    override fun requestExpire() = submit(Expire(this))

    /** Inside the connection: the deadline passed while parked. */
    internal fun expire(inside: Conn.Inside) =
        contained {
            val entry = streams.entry(stream)
            if (parked(entry) === this) {
                park(entry, Nobody)
                release()
                if (cont.isActive) timeOut()
            }
        }

    /** Inside the connection: the caller was cancelled while parked. */
    internal fun withdraw() =
        contained {
            unpark()
            disarm()
            release()
        }

    /** The same exception a 4.x stream deadline raises, built where it is thrown from, so callers see one type for both cores. */
    private fun timeOut() {
        val bound = deadline
        suspend { timedOut(bound) }.startCoroutine(Continuation(EmptyCoroutineContext) { it.onFailure(cont::resumeWithException) })
    }

    /** What this operation throws fails this operation's caller and no other: it is unparked and resumed with it. */
    private inline fun contained(body: () -> Unit) {
        try {
            body()
        } catch (failure: Exception) {
            fail(failure)
        }
    }

    private fun fail(failure: Throwable) {
        disarm()
        unpark()
        release()
        if (cont.isActive) cont.resumeWithException(failure)
    }

    private fun unpark() {
        val entry = streams.entry(stream)
        if (parked(entry) === this) park(entry, Nobody)
    }

    private fun disarm() {
        when (val armed = bound) {
            DeadlineBound.None -> Unit
            is ExpireNode -> armed.disarm()
        }
        bound = DeadlineBound.None
    }
}

internal class Withdraw(
    private val op: StreamOp<*>,
) : ConnWork() {
    override fun run(inside: Conn.Inside) = op.withdraw()

    override fun gone() = Unit
}

private class Expire(
    private val op: StreamOp<*>,
) : ConnWork() {
    override fun run(inside: Conn.Inside) = op.expire(inside)

    override fun gone() = Unit
}

/** Frees a read's buffer when its caller is cancelled after the read resumed it and before it received the result. */
private val FreeUnreceived: (Throwable, ReadResult, CoroutineContext) -> Unit = { _, result, _ ->
    if (result is ReadResult.Data) (result.buffer as PlatformBuffer).freeNativeMemory()
}

/**
 * Reads the next chunk into a buffer from the connection's factory, sized by the stream's [ReadChunk]; the caller owns a
 * returned buffer. A read that finds nothing keeps its buffer for the retry, [none] when it holds none.
 */
internal class ReadOp(
    streams: StreamMap,
    stream: QuicStreamId,
    deadline: Duration,
    cont: CancellableContinuation<ReadResult>,
    submit: (ConnWork) -> Unit,
    private val factory: BufferFactory,
    private val none: PlatformBuffer,
) : StreamOp<ReadResult>(streams, stream, deadline, cont, submit) {
    private var result: ReadResult = ReadResult.End
    private var spare: PlatformBuffer = none

    override fun attempt(
        inside: Conn.Inside,
        entry: StreamEntry,
    ): Attempt {
        if (entry.received === Received.Finished) return finished(ReadResult.End)
        val buffer = if (spare === none) factory.allocate(entry.chunk.bytes) else spare
        spare = none
        val asked = buffer.limit()
        val step =
            try {
                inside.streamRecv(stream, addressOf(buffer), asked)
            } catch (failure: Exception) {
                buffer.freeNativeMemory()
                throw failure
            }
        return step.fold(
            more = { read -> if (read == 0) holding(buffer) else data(entry, buffer, asked, read) },
            last = { read ->
                entry.received = Received.Finished
                if (read == 0) discarding(buffer, finished(ReadResult.End)) else data(entry, buffer, asked, read)
            },
            nothingYet = { holding(buffer) },
            gone = { discarding(buffer, Attempt.Failed(ConnectionReleased.failure())) },
            reset = { discarding(buffer, finished(ReadResult.Reset)) },
            error = { code -> discarding(buffer, Attempt.Failed(StreamFailure.ofRead(stream, code))) },
        )
    }

    private fun data(
        entry: StreamEntry,
        buffer: PlatformBuffer,
        asked: Int,
        read: Int,
    ): Attempt {
        entry.chunk = entry.chunk.after(asked, read)
        buffer.position(0)
        buffer.setLimit(read)
        return finished(ReadResult.Data(buffer))
    }

    private fun holding(buffer: PlatformBuffer): Attempt {
        spare = buffer
        return Attempt.Blocked
    }

    private fun discarding(
        buffer: PlatformBuffer,
        then: Attempt,
    ): Attempt {
        buffer.freeNativeMemory()
        return then
    }

    private fun finished(value: ReadResult): Attempt {
        result = value
        return Attempt.Finished
    }

    override fun deliver() = cont.resume(result, FreeUnreceived)

    override fun release() {
        if (spare !== none) spare.freeNativeMemory()
        spare = none
    }

    override fun park(
        entry: StreamEntry,
        waiter: Parked,
    ) {
        entry.reader = waiter
    }

    override fun parked(entry: StreamEntry): Parked = entry.reader
}

/** Writes what quiche takes now of [buffer]'s remaining bytes, at least one; moves its position past them. */
internal class WriteOp(
    streams: StreamMap,
    stream: QuicStreamId,
    deadline: Duration,
    cont: CancellableContinuation<BytesWritten>,
    submit: (ConnWork) -> Unit,
    private val buffer: ReadBuffer,
    private val address: Long,
    private val end: WriteEnd,
) : StreamOp<BytesWritten>(streams, stream, deadline, cont, submit) {
    private var sent = 0

    override fun attempt(
        inside: Conn.Inside,
        entry: StreamEntry,
    ): Attempt {
        val start = buffer.position()
        val length = buffer.remaining()
        val answer = inside.streamSend(stream, address + start, length, end)
        return when {
            answer.result > 0 || (answer.result == 0 && length == 0) -> {
                buffer.position(start + answer.result)
                sent = answer.result
                Attempt.Finished
            }
            answer.result == 0 || answer.result == QUICHE_ERR_DONE -> {
                inside.wantWritable(stream)
                Attempt.Blocked
            }
            else -> Attempt.Failed(StreamFailure.ofSend(stream, answer.result, answer.errorCode ?: StreamFailure.UNKNOWN))
        }
    }

    override fun deliver() = cont.resume(BytesWritten(sent))

    override fun park(
        entry: StreamEntry,
        waiter: Parked,
    ) {
        entry.writer = waiter
    }

    override fun parked(entry: StreamEntry): Parked = entry.writer
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
