package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.stream.Opened
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.Waiter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Opens the next bidirectional stream. At the peer's stream limit it waits, behind any open already waiting, until
 * the peer grants another stream or the connection ends.
 *
 * The connection lands the answer in the open's [Hand], so answering allocates nothing; the caller wraps the stream on
 * its own thread. A cancellation that comes first resumes the caller at once and hands the open back to the connection.
 *
 * Inside the connection only, except [answer] (the caller) and the job watch.
 */
@Confined
internal class OpenOp(
    private val conn: Conn,
) : ConnWork(),
    Waiter {
    private val hand = Hand<OpenAnswer>()

    /** The id of the stream opened, written before [OpenAnswer.Opened] lands and read by the caller after. */
    private var opened = 0L

    // ---- the caller's side ----

    /** The id of the opened stream once the connection answered; an open answered while it ran in place does not suspend. */
    suspend fun answer(): Long {
        val context = currentCoroutineContext()
        return when (val answer = hand.await(context) { cancelled() }) {
            is OpenAnswer.Opened -> if (context.isActive) opened else raiseCancelled(context)
            is OpenAnswer.Failed -> raiseFailure(answer.failure, answer.during)
            is OpenAnswer.Cancelled -> raiseCancelled(context)
        }
    }

    /** The cancelling thread: an open not answered yet is answered Cancelled and handed back to the connection. */
    private fun cancelled() {
        when (hand.answer(OpenAnswer.Cancelled)) {
            Landed.First -> conn.submit(Withdraw(), CallerPlace.HandToLoop)
            Landed.Late -> Unit
        }
    }

    // ---- the connection's side, inside ----

    override fun run(inside: Inside) =
        guarded(::failed) {
            when (val state = inside.streams.state) {
                is Streams.Ended -> return connectionEnded(state.end)
                is Streams.Open -> Unit
            }
            if (inside.streams.opensWaiting() > 0 || retry(inside.streams) == Retry.Again) inside.streams.waitToOpen(this)
        }

    override fun retry(table: StreamTable): Retry {
        if (hand.landed(OpenAnswer.Cancelled)) return Retry.Settled
        return when (table.tryOpen()) {
            Opened.AtLimit -> Retry.Again
            Opened.Opened -> {
                opened = table.lastOpened().id
                hand.answer(OpenAnswer.Opened)
                Retry.Settled
            }
        }
    }

    override fun connectionEnded(end: ConnEnd) {
        hand.answer(OpenAnswer.Failed(Raised.ConnEnded(end), QuicCloseCircumstance.Ended))
    }

    override fun gone(end: ConnEnd) = connectionEnded(end)

    private fun failed(failure: Exception) {
        hand.answer(OpenAnswer.Failed(Raised.Thrown(failure), QuicCloseCircumstance.Ended))
    }

    private inner class Withdraw : ConnWork() {
        override fun run(inside: Inside) = inside.streams.withdrawOpen(this@OpenOp)

        override fun gone(end: ConnEnd) = Unit
    }
}
