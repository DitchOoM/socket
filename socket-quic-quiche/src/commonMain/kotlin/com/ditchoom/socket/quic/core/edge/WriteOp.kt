package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_DONE
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.stream.WriteEnd
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Runnable
import kotlin.coroutines.resume

/** What a write hands quiche: the caller's bytes at a native address, or our FIN alone with a length of zero. */
internal sealed interface WriteSource {
    class Bytes(
        val buffer: ReadBuffer,
        val address: Long,
    ) : WriteSource

    data object FinOnly : WriteSource
}

/**
 * Writes what quiche takes now of the caller's bytes, at least one, and moves the buffer's position past them; or
 * sends our FIN with no bytes. Waits parked on the stream while quiche can take nothing. Inside the connection only,
 * except [run] (the deadline's thread).
 */
@Confined
internal class WriteOp(
    private val stream: QuicStreamId,
    private val source: WriteSource,
    private val deadline: OpDeadline,
    private val cont: CancellableContinuation<BytesWritten>,
    private val conn: Conn,
) : ConnWork(),
    Waiter,
    Runnable {
    override fun run(inside: Inside) =
        guarded(::failed) {
            when (val state = inside.streams.state) {
                is Streams.Ended -> return connectionEnded(state.end)
                is Streams.Open -> Unit
            }
            if (!cont.isActive) return
            if (attempt(inside.streams) == Retry.Again) {
                inside.streams.entry(stream).parkWriter(this)
                deadline.arm(inside, this)
            }
        }

    override fun retry(table: StreamTable): Retry {
        var answer = Retry.Settled
        guarded(::failed) {
            if (!cont.isActive) return@guarded deadline.disarm()
            answer = attempt(table)
            if (answer == Retry.Settled) deadline.disarm()
        }
        return answer
    }

    override fun connectionEnded(end: ConnEnd) {
        deadline.disarm()
        cont.fail(Raised.ConnEnded(end), QuicCloseCircumstance.Ended)
    }

    override fun gone(end: ConnEnd) = connectionEnded(end)

    /** The deadline's thread: the deadline passed; the connection expires the write from inside. */
    override fun run() = conn.submit(Expire(), CallerPlace.HandToLoop)

    /** The caller was cancelled while the write waited. */
    fun withdraw() = conn.submit(Withdraw(), CallerPlace.HandToLoop)

    private fun attempt(table: StreamTable): Retry {
        val answer =
            when (source) {
                is WriteSource.Bytes -> {
                    val start = source.buffer.position()
                    val length = source.buffer.remaining()
                    table.io.send(stream, source.address + start, length, WriteEnd.More).also {
                        if (it.result > 0) source.buffer.position(start + it.result)
                    }
                }
                is WriteSource.FinOnly -> table.io.send(stream, 0L, 0, WriteEnd.Finish)
            }
        val taken = answer.result
        return when {
            taken > 0 -> resumed(taken)
            taken == 0 && source is WriteSource.FinOnly -> resumed(0)
            taken == 0 || taken == QUICHE_ERR_DONE -> {
                table.wantWritable(stream)
                Retry.Again
            }
            else -> {
                cont.fail(Raised.WriteRefused(stream, taken, answer.errorCode ?: UNKNOWN_PEER_CODE), QuicCloseCircumstance.Ended)
                Retry.Settled
            }
        }
    }

    private fun resumed(taken: Int): Retry {
        cont.resume(BytesWritten(taken))
        return Retry.Settled
    }

    private fun failed(failure: Exception) {
        deadline.disarm()
        cont.fail(Raised.Thrown(failure), QuicCloseCircumstance.Ended)
    }

    private inner class Expire : ConnWork() {
        override fun run(inside: Inside) {
            val entry = inside.streams.entry(stream)
            if (entry.writer !== this@WriteOp) return
            entry.unpark(this@WriteOp)
            cont.fail(OpFailure.DeadlinePassed(deadline.bound), QuicCloseCircumstance.Ended)
        }

        override fun gone(end: ConnEnd) = Unit
    }

    private inner class Withdraw : ConnWork() {
        override fun run(inside: Inside) {
            inside.streams.entry(stream).unpark(this@WriteOp)
            deadline.disarm()
        }

        override fun gone(end: ConnEnd) = Unit
    }

    private companion object {
        /** The peer's code when the backend does not report it. */
        val UNKNOWN_PEER_CODE = QuicAppErrorCode(0L)
    }
}
