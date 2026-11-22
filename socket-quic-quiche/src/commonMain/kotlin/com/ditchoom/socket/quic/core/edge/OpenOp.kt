package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.stream.Opened
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.SendRefusal
import com.ditchoom.socket.quic.core.stream.Sender
import com.ditchoom.socket.quic.core.stream.StreamKind
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.stream.sender
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Opens the next stream of [kind]. At the peer's stream limit of that kind it waits, behind any open of the kind already
 * waiting, until the peer grants another stream, the connection ends, or [bound] passes; a bound that passes fails it
 * as a write's passed deadline fails, and no id is spent.
 *
 * The connection lands the answer in the open's [Hand], so answering allocates nothing; the caller wraps the stream on
 * its own thread. A cancellation that comes first resumes the caller at once and hands the open back to the connection.
 *
 * Inside the connection only, except [answer] (the caller) and the job watch.
 */
@Confined
internal class OpenOp(
    private val kind: StreamKind,
    private val bound: Duration,
    private val deadlines: OpDeadlines,
    private val conn: Conn,
) : ConnWork(),
    Waiter,
    Bounded {
    private val hand = Hand<OpenAnswer>()
    private var lease: Lease = Lease.None

    /** The id of the stream opened, written before [OpenAnswer.Opened] lands and read by the caller after. */
    private var opened = 0L

    // ---- the caller's side ----

    /** The id of the opened stream once the connection answered; an open answered while it ran in place does not suspend. */
    suspend fun answer(): Long {
        val context = currentCoroutineContext()
        return when (val answer = hand.await(context) { cancelled() }) {
            is OpenAnswer.Opened -> if (context.isActive) opened else unclaimed(context)
            is OpenAnswer.Failed -> raiseFailure(answer.failure, answer.during)
            is OpenAnswer.Cancelled -> raiseCancelled(context)
        }
    }

    /** A stream was opened for a caller cancelled meanwhile: the connection gives it back, and the caller is cancelled. */
    private fun unclaimed(context: CoroutineContext): Nothing {
        conn.submit(ResetWork(QuicStreamId(opened), UNCLAIMED), CallerPlace.HandToLoop)
        raiseCancelled(context)
    }

    /**
     * The cancelling thread: an open not answered yet is answered Cancelled and handed back to the connection,
     * ahead of anything its caller does once it resumes.
     */
    private fun cancelled() {
        hand.answer(OpenAnswer.Cancelled) { conn.submit(Withdraw(), CallerPlace.HandToLoop) }
    }

    // ---- the connection's side, inside ----

    override fun run(inside: Inside) =
        guarded(::failed) {
            when (val sender = inside.streams.sender()) {
                is Sender.Refused -> return refused(sender.why)
                is Streams.Open -> Unit
            }
            val lane = inside.streams.opens.lane(kind)
            if (lane.waits() > 0 || retry(inside.streams) == Retry.Again) {
                lane.wait(this)
                lease = deadlines.arm(this, bound, inside)
            }
        }

    override fun retry(table: StreamTable): Retry {
        if (hand.landed(OpenAnswer.Cancelled)) return Retry.Settled
        val send =
            when (val sender = table.sender()) {
                is Streams.Open -> sender.send
                is Sender.Refused -> {
                    refused(sender.why)
                    return Retry.Settled
                }
            }
        return when (table.tryOpen(kind, send)) {
            Opened.AtLimit -> Retry.Again
            Opened.Opened -> {
                opened = table.lastOpened().id
                lease = deadlines.disarm(lease)
                when (hand.answer(OpenAnswer.Opened)) {
                    Landed.First -> Unit
                    // Cancelled first: nobody will take the stream, so it goes back now.
                    Landed.Late -> table.resetBothWays(QuicStreamId(opened), UNCLAIMED)
                }
                Retry.Settled
            }
        }
    }

    override fun connectionEnded(end: ConnEnd) = settle(OpenAnswer.Failed(Raised.ConnEnded(end), QuicCloseCircumstance.Ended))

    override fun gone(end: ConnEnd) = connectionEnded(end)

    /** Inside: the bound passed while the open still waited for the peer's credit. */
    fun expired(inside: Inside) {
        if (!hand.isOpen()) return
        inside.streams.opens
            .lane(kind)
            .withdraw(this)
        settle(OpenAnswer.Failed(OpFailure.DeadlinePassed(bound), QuicCloseCircumstance.Ended))
    }

    /** The open came after the close point, or after the end: [why]. */
    private fun refused(why: SendRefusal) = settle(OpenAnswer.Failed(why.raised(), QuicCloseCircumstance.Ended))

    private fun failed(failure: Exception) = settle(OpenAnswer.Failed(Raised.Thrown(failure), QuicCloseCircumstance.Ended))

    /** Lands [answer], first one only, with the deadline given back. */
    private fun settle(answer: OpenAnswer) {
        lease = deadlines.disarm(lease)
        hand.answer(answer)
    }

    private companion object {
        /** The code a stream opened for nobody is given back with: it carries no application meaning. */
        val UNCLAIMED = QuicAppErrorCode(0L)
    }

    private inner class Withdraw : ConnWork() {
        override fun run(inside: Inside) {
            inside.streams.opens
                .lane(kind)
                .withdraw(this@OpenOp)
            lease = deadlines.disarm(lease)
        }

        override fun gone(end: ConnEnd) = Unit
    }
}
