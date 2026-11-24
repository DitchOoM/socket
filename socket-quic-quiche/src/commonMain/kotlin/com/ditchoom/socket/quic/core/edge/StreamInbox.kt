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
    Waiter {
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

    /** Written by the loop before a delivery goes to the lane, read by the lane in it. */
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

    /** Reads what quiche holds into the buffer and hands it to the lane, unless a delivery is still out. */
    private fun drain(table: StreamTable): Retry {
        if (out.load() == OUT) return Retry.Again
        length = 0
        ending = InboxEnding.Not
        while (LENT_ROOM - length > 0) {
            val read =
                table.io.recv(stream, address + length, LENT_ROOM - length).fold(
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
            length += read
            if (read == 0 || ending !is InboxEnding.Not) break
        }
        val ended = ending !is InboxEnding.Not
        if (length > 0 || ended) {
            out.store(OUT)
            lane.dispatch(HandlerSite.Unnamed, deliver)
        }
        return if (ended) Retry.Settled else Retry.Again
    }

    override fun connectionEnded(end: ConnEnd) {
        if (out.load() == OUT) return
        length = 0
        ending = InboxEnding.With(ScopedRead.End.ConnectionEnded(publicEndOf(end)))
        out.store(OUT)
        lane.dispatch(HandlerSite.Unnamed, deliver)
    }

    override fun gone(end: ConnEnd) = connectionEnded(end)

    // ---- the lane ----

    /** Hands the receiver what the loop read, then the end if it came, else gives the stream back to the loop. */
    private fun deliverOnLane() {
        if (length > 0) {
            lent.position(0)
            lent.setLimit(length)
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
        }
        when (val now = ending) {
            is InboxEnding.With -> {
                receiver.onEnd(now.end)
                letGo()
            }
            is InboxEnding.Not -> {
                out.store(IDLE)
                connection.handToLoop(again)
            }
        }
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

        /** The stream's receive buffer: what one call is lent at most. */
        const val LENT_ROOM = 65_536
    }
}
