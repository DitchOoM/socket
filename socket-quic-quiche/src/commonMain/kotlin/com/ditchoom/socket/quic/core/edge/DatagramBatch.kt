package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.DatagramRefusal
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicDatagramBatch
import com.ditchoom.socket.quic.QuicDatagramRefused
import com.ditchoom.socket.quic.core.conn.BatchWaiter
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Datagrams
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.conn.Receipt
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.outsideTheBatch

/** What a batch receive came to; what it took is in its [LentBatch]. */
internal sealed interface BatchAnswer : HandAnswer {
    data object Taken : BatchAnswer

    class Ended(
        val end: ConnEnd,
    ) : BatchAnswer

    class Failed(
        val failure: OpFailure,
        val during: QuicCloseCircumstance,
    ) : BatchAnswer

    data object Cancelled : BatchAnswer
}

/**
 * The datagrams one receive was lent: the connection's own queue slots, read in place. Filled inside the connection
 * while a receive is under way and read by that receive's caller once it was answered; one receive runs at a time, and
 * the connection takes the slots back when the next batch is lent. Its lists grow to the most one batch held, then are
 * reused.
 */
@Confined
internal class LentBatch : QuicDatagramBatch {
    private val buffers = ArrayList<PlatformBuffer>()
    private var lengths = IntArray(INITIAL)

    /** How many datagrams the batch shows: those lent, while its caller reads them; 0 otherwise. */
    private var shown = 0

    override val size: Int get() = shown

    override fun get(index: Int): ReadBuffer {
        if (index !in 0 until shown) outsideTheBatch()
        return buffers[index].apply {
            position(0)
            setLimit(lengths[index])
        }
    }

    /** Inside: the next batch begins; what the last one held goes back with the slots. */
    fun clear() {
        buffers.clear()
        shown = 0
    }

    /** Inside: [buffer] holds a datagram [length] bytes long. */
    fun add(
        buffer: PlatformBuffer,
        length: Int,
    ) {
        if (buffers.size == lengths.size) lengths = lengths.copyOf(lengths.size * 2)
        lengths[buffers.size] = length
        buffers.add(buffer)
    }

    /** The caller, once answered: its block may read what was lent. */
    fun show(): Int {
        shown = buffers.size
        return shown
    }

    /** The caller, as its block returns: the batch reads as empty from here on. */
    fun hide() {
        shown = 0
    }

    private companion object {
        const val INITIAL = 64
    }
}

/**
 * One batch receive: lent every datagram the connection holds, and answered once; with none held it parks, and once
 * datagrams arrived it runs again later in the same pass, so it is lent everything that pass read. A cancellation that
 * comes first resumes the caller at once and takes the parked receive back, ahead of the caller's next receive.
 *
 * Inside the connection only, except [hand], which its caller awaits, and the job watch.
 */
@Confined
internal class BatchOp(
    private val batch: LentBatch,
    private val conn: Conn,
) : ConnWork(),
    BatchWaiter {
    /** The caller awaits the answer here, in its own frame: [Hand.await] is inline. */
    val hand = Hand<BatchAnswer>()

    /** The cancelling thread: an open receive is answered Cancelled, and taken back from the box. */
    fun cancelled() {
        hand.answer(BatchAnswer.Cancelled) { conn.submit(Withdraw(), CallerPlace.HandToLoop) }
    }

    override fun run(inside: Inside) =
        guarded(::failed) {
            if (hand.landed(BatchAnswer.Cancelled)) return
            when (val datagrams = inside.datagrams) {
                is Datagrams.Off -> refused(DatagramRefusal.NotNegotiated)
                is Datagrams.On -> {
                    batch.clear()
                    when (datagrams.box.lend(this, inside.now())) {
                        Receipt.Delivered -> settle(BatchAnswer.Taken)
                        Receipt.Parked -> Unit
                    }
                }
            }
        }

    override fun lent(
        buffer: PlatformBuffer,
        length: Int,
    ) = batch.add(buffer, length)

    /** Inside: datagrams arrived while this was parked; it is lent them when it runs again, after the pass's reads. */
    override fun arrived() = conn.submit(this, CallerPlace.HandToLoop)

    private fun refused(why: DatagramRefusal) =
        settle(BatchAnswer.Failed(Raised.Thrown(QuicDatagramRefused(why)), QuicCloseCircumstance.Ended))

    private fun failed(failure: Exception) = settle(BatchAnswer.Failed(Raised.Thrown(failure), QuicCloseCircumstance.Ended))

    override fun connectionEnded(end: ConnEnd) = settle(BatchAnswer.Ended(end))

    override fun gone(end: ConnEnd) = connectionEnded(end)

    private fun settle(answer: BatchAnswer) {
        hand.answer(answer)
    }

    /** Takes back a receive whose caller was cancelled while it was parked. */
    private inner class Withdraw : ConnWork() {
        override fun run(inside: Inside) {
            when (val datagrams = inside.datagrams) {
                is Datagrams.Off -> Unit
                is Datagrams.On -> datagrams.box.withdraw(this@BatchOp)
            }
        }

        override fun gone(end: ConnEnd) = Unit
    }
}
