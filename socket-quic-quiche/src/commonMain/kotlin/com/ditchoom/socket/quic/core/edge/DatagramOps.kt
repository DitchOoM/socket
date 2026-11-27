package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramRefusal
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicDatagramRefused
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.DatagramWaiter
import com.ditchoom.socket.quic.core.conn.Datagrams
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.conn.Interest
import com.ditchoom.socket.quic.core.conn.Receipt
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.CoroutineContext

/** What a datagram receive came to. */
internal sealed interface ReceiveAnswer : HandAnswer {
    /** A datagram arrived in [buffer], which is the caller's from here on; made when the receive was, so answering allocates nothing. */
    class Arrived(
        val buffer: PlatformBuffer,
    ) : ReceiveAnswer

    /** The connection ended with [end]: no datagram will arrive. */
    class Ended(
        val end: ConnEnd,
    ) : ReceiveAnswer

    class Failed(
        val failure: OpFailure,
        val during: QuicCloseCircumstance,
    ) : ReceiveAnswer

    /** The caller's job was cancelled first: the connection takes the receive back and frees its buffer. */
    data object Cancelled : ReceiveAnswer
}

private enum class Memory { Held, Given }

/**
 * Receives the next datagram into a buffer the caller made, parked in the connection until one arrives. The answer is
 * made with the buffer, and the connection lands it in the receive's [Hand], so a parked receive is answered without
 * allocating. A receive whose caller was cancelled frees its buffer, once, inside the connection.
 *
 * Inside the connection only, except [answer] (the caller) and the job watch.
 */
@Confined
internal class DatagramReceiveOp(
    private val into: ReceiveAnswer.Arrived,
    override val address: Long,
    override val capacity: Int,
    private val conn: Conn,
    private val caller: CoroutineContext,
) : ConnWork(),
    DatagramWaiter {
    private val hand = Hand<ReceiveAnswer>()
    private var memory = Memory.Held

    /** The receive's answer; a caller cancelled meanwhile frees what arrived and raises. */
    suspend fun answer(): ReceiveAnswer {
        val answer = hand.await(caller) { cancelled() }
        val context = currentCoroutineContext()
        return when (answer) {
            is ReceiveAnswer.Arrived -> {
                if (!caller.isActive) {
                    answer.buffer.freeIfNeeded()
                    raiseCancelled(context)
                }
                answer
            }
            is ReceiveAnswer.Cancelled -> raiseCancelled(context)
            is ReceiveAnswer.Ended, is ReceiveAnswer.Failed -> answer
        }
    }

    /**
     * The cancelling thread: a receive not answered yet is answered Cancelled and handed back to the connection,
     * ahead of anything its caller does once it resumes.
     */
    private fun cancelled() {
        hand.answer(ReceiveAnswer.Cancelled) { conn.submit(Withdraw(), CallerPlace.HandToLoop) }
    }

    override fun run(inside: Inside) =
        guarded(::failed) {
            when (val datagrams = inside.datagrams) {
                is Datagrams.Off -> refused(DatagramRefusal.NotNegotiated)
                is Datagrams.On ->
                    when (interest()) {
                        Interest.Withdrawn -> discard()
                        Interest.Wanted ->
                            when (datagrams.box.receive(this, inside.now())) {
                                Receipt.Delivered, Receipt.Parked -> Unit
                            }
                    }
            }
        }

    override fun gone(end: ConnEnd) = connectionEnded(end)

    override fun interest(): Interest = if (hand.landed(ReceiveAnswer.Cancelled)) Interest.Withdrawn else Interest.Wanted

    override fun arrived(length: Int) {
        into.buffer.position(0)
        into.buffer.setLimit(length)
        when (hand.answer(into)) {
            Landed.First -> memory = Memory.Given
            Landed.Late -> discard()
        }
    }

    override fun refused(code: Int) = refused(DatagramRefusal.Quiche(code))

    private fun refused(why: DatagramRefusal) {
        discard()
        settle(ReceiveAnswer.Failed(Raised.Thrown(QuicDatagramRefused(why)), QuicCloseCircumstance.Ended))
    }

    override fun discard() {
        when (memory) {
            Memory.Held -> {
                memory = Memory.Given
                into.buffer.freeNativeMemory()
            }
            Memory.Given -> Unit
        }
    }

    override fun connectionEnded(end: ConnEnd) {
        discard()
        settle(ReceiveAnswer.Ended(end))
    }

    private fun failed(failure: Exception) {
        discard()
        settle(ReceiveAnswer.Failed(Raised.Thrown(failure), QuicCloseCircumstance.Ended))
    }

    private fun settle(answer: ReceiveAnswer) {
        hand.answer(answer)
    }

    /** Takes back a receive whose caller was cancelled: unparks it and frees its buffer. */
    private inner class Withdraw : ConnWork() {
        override fun run(inside: Inside) {
            when (val datagrams = inside.datagrams) {
                is Datagrams.Off -> Unit
                is Datagrams.On -> datagrams.box.withdraw(this@DatagramReceiveOp)
            }
            discard()
        }

        override fun gone(end: ConnEnd) = Unit
    }
}
