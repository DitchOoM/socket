package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.BytesWritten
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
import com.ditchoom.socket.quic.core.stream.Sender
import com.ditchoom.socket.quic.core.stream.ShutdownSide
import com.ditchoom.socket.quic.core.stream.Side
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.stream.WriteEnd
import com.ditchoom.socket.quic.core.stream.code
import com.ditchoom.socket.quic.core.stream.peer
import com.ditchoom.socket.quic.core.stream.sender
import kotlinx.coroutines.isActive
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * What a write hands quiche: the caller's bytes at a native address, more to follow or ending our side with the last
 * of them ([end]), or our FIN alone with a length of zero.
 */
internal sealed interface WriteSource {
    class Bytes(
        val buffer: ReadBuffer,
        val address: Long,
        val end: WriteEnd,
    ) : WriteSource

    data object FinOnly : WriteSource
}

/**
 * Writes what quiche takes now of the caller's bytes, at least one, and moves the buffer's position past them; or
 * sends our FIN with no bytes. Bytes that end our side are all written, the FIN going with the last of them: every
 * send offers quiche the FIN, and quiche keeps it only with a send that took every byte offered. Waits parked on the
 * stream while quiche can take nothing.
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
    internal val caller: CoroutineContext,
) : ConnWork(),
    Waiter,
    Bounded {
    internal val hand = Hand<WriteAnswer>()
    private var lease: Lease = Lease.None
    private var fin = Fin.Open

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
    internal fun withdraw() = conn.submit(Withdraw(), CallerPlace.HandToLoop)

    /**
     * The write's own answer, for a caller that reports it as a value; a cancelled caller raises as [answer] does.
     * Inline, so it runs in its caller's suspend frame and makes none of its own.
     */
    suspend inline fun settled(): WriteAnswer {
        val answer = hand.await(caller) { withdraw() }
        return if (caller.isActive && answer !is WriteAnswer.Cancelled) answer else raiseCancelled(caller)
    }

    private fun resultOf(answer: WriteAnswer): BytesWritten =
        when (answer) {
            is WriteAnswer.Written -> if (caller.isActive) BytesWritten(taken()) else raiseCancelled(caller)
            is WriteAnswer.Failed -> raiseFailure(answer.failure, answer.during)
            is WriteAnswer.Cancelled -> raiseCancelled(caller)
        }

    /**
     * The answer of a write that ends our side: every byte and the FIN, or cut short after quiche took part of the bytes.
     * A failure before quiche took any byte, and a cancellation, raise as [answer] does.
     */
    suspend fun finishAnswer(): Finished {
        val answer = hand.await(caller) { withdraw() }
        return when (answer) {
            is WriteAnswer.Written -> if (caller.isActive) Finished.All(taken()) else raiseCancelled(caller)
            is WriteAnswer.Failed ->
                when (fin) {
                    Fin.Cut -> Finished.Cut(taken(), answer.failure)
                    Fin.Open, Fin.Taken -> raiseFailure(answer.failure, answer.during)
                }
            is WriteAnswer.Cancelled -> raiseCancelled(caller)
        }
    }

    /** Where the write left our side, read once it answered: the connection sets it before it lands any answer. */
    fun fin(): Fin = fin

    private fun taken(): Int =
        when (source) {
            is WriteSource.Bytes -> source.buffer.position() - start
            is WriteSource.FinOnly -> 0
        }

    // ---- the connection's side, inside ----

    override fun run(inside: Inside) =
        guarded({ failed(it, inside.streams) }) {
            when (val state = inside.streams.state) {
                is Streams.Ended -> return connectionEnded(state.end)
                is Streams.Live -> Unit
            }
            if (!caller.isActive) return settle(WriteAnswer.Cancelled)
            if (attempt(inside.streams) == Retry.Again) {
                inside.streams.entry(stream).parkWriter(this)
                lease = deadlines.arm(this, bound, inside)
            }
        }

    override fun retry(table: StreamTable): Retry {
        var answer = Retry.Settled
        guarded({ failed(it, table) }) {
            if (!caller.isActive) {
                lease = deadlines.disarm(lease)
                cut(table)
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
        val send =
            when (val sender = table.sender()) {
                is Streams.Open -> sender.send
                is Sender.Refused -> {
                    lease = deadlines.disarm(lease)
                    cut(table)
                    settle(WriteAnswer.Failed(sender.why.raised(), QuicCloseCircumstance.Ended))
                    return Retry.Settled
                }
            }
        val answer =
            when (source) {
                is WriteSource.Bytes -> {
                    val at = source.buffer.position()
                    val length = source.buffer.remaining()
                    send.send(stream, source.address + at, length, source.end).also {
                        if (it.code() > 0) source.buffer.position(at + it.code())
                    }
                }
                is WriteSource.FinOnly -> send.send(stream, 0L, 0, WriteEnd.Finish)
            }
        val taken = answer.code()
        return when {
            taken > 0 -> took(table)
            taken == 0 && source is WriteSource.FinOnly -> took(table)
            taken == 0 || taken == QUICHE_ERR_DONE -> waitWritable(table)
            else -> {
                val peer = answer.peer()
                if (taken == QUICHE_ERR_STREAM_STOPPED) table.stopped(stream)
                settle(WriteAnswer.Failed(Raised.WriteRefused(stream, taken, peer), QuicCloseCircumstance.Ended))
                Retry.Settled
            }
        }
    }

    /** quiche took bytes, or our FIN alone: the write is answered, unless bytes that end our side are left. */
    private fun took(table: StreamTable): Retry =
        when (source) {
            is WriteSource.FinOnly -> finished(table)
            is WriteSource.Bytes ->
                when (source.end) {
                    WriteEnd.More -> written()
                    WriteEnd.Finish -> if (source.buffer.remaining() == 0) finished(table) else waitWritable(table)
                }
        }

    private fun finished(table: StreamTable): Retry {
        table.sendEnded(stream, Side.Finished)
        fin = Fin.Taken
        return written()
    }

    /**
     * A write that ends our side stops after quiche took part of its bytes and before the last: the peer would read the
     * part as the whole, so our side is reset instead.
     */
    private fun cut(table: StreamTable) {
        if (source !is WriteSource.Bytes || source.end == WriteEnd.More || fin != Fin.Open || taken() == 0) return
        table.io.shutdown(stream, ShutdownSide.Write, FINISH_CUT_CODE.value)
        table.sendEnded(stream, Side.Reset(FINISH_CUT_CODE))
        fin = Fin.Cut
    }

    private fun waitWritable(table: StreamTable): Retry {
        table.wantWritable(stream)
        return Retry.Again
    }

    private fun written(): Retry {
        settle(WriteAnswer.Written)
        return Retry.Settled
    }

    /** Lands [answer]; one that comes after another is dropped, and none holds anything to free. */
    private fun settle(answer: WriteAnswer) {
        hand.answer(answer)
    }

    private fun failed(
        failure: Exception,
        table: StreamTable,
    ) {
        lease = deadlines.disarm(lease)
        cut(table)
        settle(WriteAnswer.Failed(Raised.Thrown(failure), QuicCloseCircumstance.Ended))
    }

    /** Inside: the bound passed while the write still waited parked. */
    fun expired(inside: Inside) {
        if (inside.streams.writerOf(stream) !== this) return
        lease = deadlines.disarm(lease)
        inside.streams.unpark(stream, this)
        cut(inside.streams)
        settle(WriteAnswer.Failed(OpFailure.DeadlinePassed(bound), QuicCloseCircumstance.Ended))
    }

    /** Takes back a write whose caller was cancelled, then answers it: from here the source is the caller's alone. */
    private inner class Withdraw : ConnWork() {
        override fun run(inside: Inside) {
            inside.streams.unpark(stream, this@WriteOp)
            lease = deadlines.disarm(lease)
            cut(inside.streams)
            settle(WriteAnswer.Cancelled)
        }

        override fun gone(end: ConnEnd) = settle(WriteAnswer.Cancelled)
    }
}
