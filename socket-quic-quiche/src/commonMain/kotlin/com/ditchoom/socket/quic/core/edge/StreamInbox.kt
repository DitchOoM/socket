@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamReceiver
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Queue
import com.ditchoom.socket.quic.core.sched.HandlerLane
import com.ditchoom.socket.quic.core.sched.HandlerSite
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.Side
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** How a delivery ends the stream for its receiver, if it does. */
private sealed interface InboxEnding {
    data object Not : InboxEnding

    class With(
        val end: ScopedRead.End,
    ) : InboxEnding
}

/** Whether a receiver returned from its call or threw out of it. */
private enum class InboxReturned { No, Yes }

/**
 * Hands a stream's bytes to its [receiver] on the connection's handler [lane]: the loop reads everything quiche holds
 * for the stream into one buffer the stream keeps ([LENT_ROOM] bytes), hands it to the lane, and reads nothing more
 * until the receiver returned. So a receiver slow to return keeps the stream's flow-control window shut, and the bytes
 * held for it are bounded by the window and this buffer. The lane gives the stream back to the loop after each call.
 *
 * A receiver takes what it can, as the buffer's position says; the rest stays in the buffer and is offered again,
 * first in the next call: at once when the receiver took some, else with the next bytes that arrive or once the
 * stream's own send queue makes room (a reply the queue could not take). Until the held bytes are taken, the buffer's
 * room is what the loop reads into, so a receiver that takes nothing holds the peer back.
 *
 * The loop owns the buffer while no delivery is out, the lane while one is; the hand-offs between them order the two.
 */
@Queue
internal class StreamInbox(
    private val connection: CoreConnection,
    private val stream: QuicStreamId,
    private val lane: HandlerLane,
    private val receiver: QuicStreamReceiver,
    private val failedCode: com.ditchoom.socket.quic.QuicAppErrorCode,
) : ConnWork(),
    Waiter,
    RoomListener {
    private val lent = connection.bufferFactory.allocate(LENT_ROOM)
    private val address =
        when (val at = NativeBytes.addressOf(lent)) {
            is ReadAddress.At -> at.address
            is ReadAddress.NotAddressable -> {
                lent.freeNativeMemory()
                raise(Raised.FactoryNotNative(lent), QuicCloseCircumstance.Ended)
            }
        }
    private val out = AtomicInt(IDLE)

    /** Whether the last call took none of what it was offered: held bytes then wait for new bytes or send room. */
    private val stalled = AtomicInt(MOVING)

    /** Written by the loop before a delivery goes to the lane, and by the lane in it; the hand-offs order the two. */
    private var start = 0
    private var held = 0
    private var length = 0
    private var ending: InboxEnding = InboxEnding.Not
    private val deliver = Runnable { deliverOnLane() }
    private val again = Again()

    // ---- the loop ----

    override fun run(inside: Inside) {
        val entry = inside.streams.entry(stream)
        when (drain(inside.streams)) {
            Retry.Again -> entry.parkReader(this)
            Retry.Settled -> inside.streams.collect(entry)
        }
    }

    override fun retry(table: StreamTable): Retry = drain(table)

    /** Reads what quiche holds after the held bytes and hands both to the lane, unless a delivery is still out. */
    private fun drain(table: StreamTable): Retry {
        if (out.load() == OUT) return Retry.Again
        if (start > 0 && start + held > LENT_ROOM - LENT_ROOM / 4) compact()
        var end = start + held
        while (ending is InboxEnding.Not && LENT_ROOM - end > 0) {
            val read =
                table.io.recv(stream, address + end, LENT_ROOM - end).fold(
                    more = { it },
                    last = { read ->
                        table.entry(stream).finished()
                        ending = InboxEnding.With(ScopedRead.End.PeerFinished)
                        read
                    },
                    nothingYet = { 0 },
                    gone = {
                        ending = InboxEnding.With(ScopedRead.End.ConnectionEnded(publicEndOf(ConnEnd.Released)))
                        0
                    },
                    reset = { code ->
                        table.entry(stream).receiveEnded(Side.Reset(code))
                        ending = InboxEnding.With(ScopedRead.End.PeerReset(code))
                        0
                    },
                    error = {
                        // quiche refused the read of an open stream: nothing more comes on it.
                        ending = InboxEnding.With(ScopedRead.End.ConnectionEnded(publicEndOf(ConnEnd.Released)))
                        0
                    },
                )
            end += read
            if (read == 0) break
        }
        val fresh = end - start - held
        length = held + fresh
        val ended = ending !is InboxEnding.Not
        val moving = stalled.load() == MOVING
        if (fresh > 0 || (ended && held == 0) || (moving && (held > 0 || ended))) {
            out.store(OUT)
            lane.dispatch(HandlerSite.Unnamed, deliver)
        }
        return if (ended) Retry.Settled else Retry.Again
    }

    /** Moves the held bytes to the buffer's start, so the room after them is whole again. */
    private fun compact() {
        lent.setLimit(LENT_ROOM)
        for (i in 0 until held) {
            val b = lent.get(start + i)
            lent.position(i)
            lent.writeByte(b)
        }
        start = 0
    }

    override fun connectionEnded(end: ConnEnd) {
        if (out.load() == OUT) return
        start = 0
        held = 0
        length = 0
        ending = InboxEnding.With(ScopedRead.End.ConnectionEnded(publicEndOf(end)))
        out.store(OUT)
        lane.dispatch(HandlerSite.Unnamed, deliver)
    }

    override fun gone(end: ConnEnd) = connectionEnded(end)

    // ---- the lane ----

    /**
     * Hands the receiver what the loop read after what it held, keeps what the receiver left, then the end once nothing
     * is held, else gives the stream back to the loop.
     */
    private fun deliverOnLane() {
        if (length > 0) {
            lent.setLimit(start + length)
            lent.position(start)
            var returned = InboxReturned.No
            try {
                receiver.onBytes(lent)
                returned = InboxReturned.Yes
            } finally {
                when (returned) {
                    InboxReturned.Yes -> Unit
                    InboxReturned.No -> {
                        // A receiver that throws gives the stream up: the peer is told to stop, and nothing more is called.
                        connection.stopReading(stream, failedCode)
                        letGo()
                    }
                }
            }
            val taken = (lent.position() - start).coerceIn(0, length)
            held = length - taken
            start = if (held == 0) 0 else start + taken
            stalled.store(if (taken == 0 && held > 0) STALLED else MOVING)
        }
        when (val now = ending) {
            is InboxEnding.With ->
                if (held == 0) {
                    receiver.onEnd(now.end)
                    return letGo()
                }
            is InboxEnding.Not -> Unit
        }
        out.store(IDLE)
        // A receiver that took none waits for new bytes, which wake the parked reader, or for send room ([roomMade]).
        if (stalled.load() == MOVING) connection.handToLoop(again)
    }

    /** The stream's send queue made room: held bytes a stalled receiver could not reply to are offered again. */
    fun roomMade() {
        if (stalled.compareAndSet(STALLED, MOVING)) connection.handToLoop(again)
    }

    private fun letGo() {
        lent.freeIfNeeded()
        connection.handlers.left()
    }

    /** Back inside after a delivery: reads what quiche held meanwhile, since no new datagram may come to wake the reader. */
    private inner class Again : ConnWork() {
        override fun run(inside: Inside) {
            if (drain(inside.streams) == Retry.Settled) inside.streams.unpark(stream, this@StreamInbox)
        }

        /** The connection ended with no delivery out: the receiver hears the end now, on the lane that handed this. */
        override fun gone(end: ConnEnd) {
            receiver.onEnd(ScopedRead.End.ConnectionEnded(publicEndOf(end)))
            letGo()
        }
    }

    private companion object {
        const val IDLE = 0
        const val OUT = 1
        const val MOVING = 0
        const val STALLED = 1

        /** The stream's receive buffer: what one call is lent at most. */
        const val LENT_ROOM = 65_536
    }
}
