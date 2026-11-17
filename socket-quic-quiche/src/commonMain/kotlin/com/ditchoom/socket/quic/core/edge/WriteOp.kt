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
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_STREAM_STOPPED
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.Side
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.stream.WriteEnd
import kotlinx.coroutines.isActive
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

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
 * sends our FIN with no bytes. Waits parked on the stream while quiche can take nothing.
 *
 * The connection lands the answer in the write's [Hand], so answering allocates nothing, and every answer lands inside
 * the connection after its last touch of the caller's buffer. A cancelled caller is answered once the connection has
 * taken the write back, so a caller that resumed, however it resumed, owns its buffer again.
 *
 * Inside the connection only, except [answer] (the caller) and the job watch.
 */
@Confined
internal class WriteOp(
    private val stream: QuicStreamId,
    private val source: WriteSource,
    private val bound: Duration,
    private val deadlines: OpDeadlines,
    private val conn: Conn,
    private val caller: CoroutineContext,
) : ConnWork(),
    Waiter,
    Bounded {
    private val hand = Hand<WriteAnswer>()
    private var lease: Lease = Lease.None

    /** Where the source stood when the caller handed it over: what quiche took is how far it moved from here. */
    private val start =
        when (source) {
            is WriteSource.Bytes -> source.buffer.position()
            is WriteSource.FinOnly -> 0
        }

    // ---- the caller's side ----

    /**
     * The write's result once the connection answered it; a write answered while it ran in place does not suspend. While
     * it waits, a cancellation of the caller's job asks the connection to take the write back.
     */
    suspend fun answer(): BytesWritten = resultOf(hand.await(caller) { withdraw() })

    /** The cancelling thread: the connection takes the write back from inside, and answers it there. */
    private fun withdraw() = conn.submit(Withdraw(), CallerPlace.HandToLoop)

    private fun resultOf(answer: WriteAnswer): BytesWritten =
        when (answer) {
            is WriteAnswer.Written -> if (caller.isActive) BytesWritten(taken()) else raiseCancelled(caller)
            is WriteAnswer.Failed -> raiseFailure(answer.failure, answer.during)
            is WriteAnswer.Cancelled -> raiseCancelled(caller)
        }

    private fun taken(): Int =
        when (source) {
            is WriteSource.Bytes -> source.buffer.position() - start
            is WriteSource.FinOnly -> 0
        }

    // ---- the connection's side, inside ----

    override fun run(inside: Inside) =
        guarded(::failed) {
            when (val state = inside.streams.state) {
                is Streams.Ended -> return connectionEnded(state.end)
                is Streams.Open -> Unit
            }
            if (!caller.isActive) return settle(WriteAnswer.Cancelled)
            if (attempt(inside.streams) == Retry.Again) {
                inside.streams.entry(stream).parkWriter(this)
                lease = deadlines.arm(this, bound, inside)
            }
        }

    override fun retry(table: StreamTable): Retry {
        var answer = Retry.Settled
        guarded(::failed) {
            if (!caller.isActive) {
                lease = deadlines.disarm(lease)
                return@guarded settle(WriteAnswer.Cancelled)
            }
            answer = attempt(table)
            if (answer == Retry.Settled) lease = deadlines.disarm(lease)
        }
        return answer
    }

    override fun connectionEnded(end: ConnEnd) {
        lease = deadlines.disarm(lease)
        settle(WriteAnswer.Failed(Raised.ConnEnded(end), QuicCloseCircumstance.Ended))
    }

    override fun gone(end: ConnEnd) = connectionEnded(end)

    private fun attempt(table: StreamTable): Retry {
        val answer =
            when (source) {
                is WriteSource.Bytes -> {
                    val at = source.buffer.position()
                    val length = source.buffer.remaining()
                    table.io.send(stream, source.address + at, length, WriteEnd.More).also {
                        if (it.result > 0) source.buffer.position(at + it.result)
                    }
                }
                is WriteSource.FinOnly -> table.io.send(stream, 0L, 0, WriteEnd.Finish)
            }
        val taken = answer.result
        return when {
            taken > 0 -> written()
            taken == 0 && source is WriteSource.FinOnly -> {
                table.sendEnded(stream, Side.Finished)
                written()
            }
            taken == 0 || taken == QUICHE_ERR_DONE -> {
                table.wantWritable(stream)
                Retry.Again
            }
            else -> {
                val peer = answer.errorCode ?: UNKNOWN_PEER_CODE
                if (taken == QUICHE_ERR_STREAM_STOPPED) table.stopped(stream)
                settle(WriteAnswer.Failed(Raised.WriteRefused(stream, taken, peer), QuicCloseCircumstance.Ended))
                Retry.Settled
            }
        }
    }

    private fun written(): Retry {
        settle(WriteAnswer.Written)
        return Retry.Settled
    }

    /** Lands [answer]; one that comes after another is dropped, and none holds anything to free. */
    private fun settle(answer: WriteAnswer) {
        hand.answer(answer)
    }

    private fun failed(failure: Exception) {
        lease = deadlines.disarm(lease)
        settle(WriteAnswer.Failed(Raised.Thrown(failure), QuicCloseCircumstance.Ended))
    }

    /** Inside: the bound passed while the write still waited parked. */
    fun expired(inside: Inside) {
        if (inside.streams.writerOf(stream) !== this) return
        lease = deadlines.disarm(lease)
        inside.streams.unpark(stream, this)
        settle(WriteAnswer.Failed(OpFailure.DeadlinePassed(bound), QuicCloseCircumstance.Ended))
    }

    /** Takes back a write whose caller was cancelled, then answers it: from here the source is the caller's alone. */
    private inner class Withdraw : ConnWork() {
        override fun run(inside: Inside) {
            inside.streams.unpark(stream, this@WriteOp)
            lease = deadlines.disarm(lease)
            settle(WriteAnswer.Cancelled)
        }

        override fun gone(end: ConnEnd) = settle(WriteAnswer.Cancelled)
    }

    private companion object {
        /** The peer's code when the backend does not report it. */
        val UNKNOWN_PEER_CODE = QuicAppErrorCode(0L)
    }
}
