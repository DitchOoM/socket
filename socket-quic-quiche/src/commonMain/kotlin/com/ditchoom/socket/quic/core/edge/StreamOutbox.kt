@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.StreamRoom
import com.ditchoom.socket.quic.StreamRoomWait
import com.ditchoom.socket.quic.StreamSend
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Queue
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_DONE
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_STREAM_STOPPED
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.Sender
import com.ditchoom.socket.quic.core.stream.Side
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.stream.WriteEnd
import com.ditchoom.socket.quic.core.stream.code
import com.ditchoom.socket.quic.core.stream.peer
import com.ditchoom.socket.quic.core.stream.sender
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * A stream's send queue: [trySend] copies bytes in from any thread and returns at once; the loop hands them to quiche
 * in order as the peer's flow control and the congestion window allow, then our FIN when it was asked for. One sender
 * and the loop: the sender owns the tail, the loop the head. Bounded: [OUTBOX_CAPACITY] bytes at most wait here.
 */
@Queue
internal class StreamOutbox(
    private val connection: CoreConnection,
    private val stream: QuicStreamId,
) : RoomSource {
    private val state = AtomicReference<OutboxState>(OutboxState.Open)
    private val ring = AtomicReference<OutboxRing>(OutboxRing.None)
    private val head = AtomicLong(0L)
    private val tail = AtomicLong(0L)
    private val scheduled = AtomicInt(OUTBOX_IDLE)
    private val parked = AtomicReference<OutboxParked>(OutboxParked.Nobody)
    private val feed = Feed()
    private val listener = AtomicReference<RoomListener>(NoRoomListener)
    private val full = StreamSend.NotSent.WindowFull(StreamRoomWait { awaitRoom(it) })

    /** The stream's receiver, told each time the loop hands quiche queued bytes. */
    fun listen(room: RoomListener) = listener.store(room)

    // ---- the sender ----

    fun trySend(bytes: ReadBuffer): StreamSend = queue(bytes, WriteEnd.More)

    /** [trySend] of [lastBytes] that asks for our FIN in the same step, so the loop hands quiche both at once. */
    fun tryFinish(lastBytes: ReadBuffer): StreamSend = queue(lastBytes, WriteEnd.Finish)

    /**
     * Copies what fits of [bytes] in. With [WriteEnd.Finish] and all fitting, the FIN is asked for before the bytes are
     * published: the loop reads the tail before the state, so it never sees the last bytes without their FIN.
     */
    private fun queue(
        bytes: ReadBuffer,
        end: WriteEnd,
    ): StreamSend {
        noteEnd()
        when (val now = state.load()) {
            is OutboxState.Open -> Unit
            is OutboxState.FinishAsked, is OutboxState.FinTaken, is OutboxState.ResetHere -> return StreamSend.NotSent.Finished
            is OutboxState.Over -> return now.why
        }
        if (bytes.remaining() == 0) return if (end == WriteEnd.Finish) tryFinish() else StreamSend.Sent
        val queued = tail.load()
        val taking = minOf(OUTBOX_CAPACITY - (queued - head.load()).toInt(), bytes.remaining())
        if (taking == 0) return full
        val all = taking == bytes.remaining()
        memory().copyIn(bytes, queued, taking)
        if (all && end == WriteEnd.Finish) state.compareAndSet(OutboxState.Open, OutboxState.FinishAsked)
        tail.store(queued + taking)
        schedule()
        return if (all) StreamSend.Sent else full
    }

    fun tryFinish(): StreamSend {
        noteEnd()
        while (true) {
            when (val now = state.load()) {
                is OutboxState.Open -> if (state.compareAndSet(now, OutboxState.FinishAsked)) break
                is OutboxState.FinishAsked, is OutboxState.FinTaken, is OutboxState.ResetHere -> return StreamSend.NotSent.Finished
                is OutboxState.Over -> return now.why
            }
        }
        schedule()
        return StreamSend.Sent
    }

    /** A connection that ended ends the direction too, before anything more is queued. */
    private fun noteEnd() {
        when (val end = connection.end()) {
            is EdgeEnd.Ended -> if (state.load() !is OutboxState.Over) over(StreamSend.NotSent.ConnectionEnded(publicEndOf(end.end)))
            is EdgeEnd.Open -> Unit
        }
    }

    /** This side resets the direction: what waits in the queue is dropped. */
    fun resetHere() {
        while (true) {
            when (val now = state.load()) {
                is OutboxState.Open, is OutboxState.FinishAsked -> if (state.compareAndSet(now, OutboxState.ResetHere)) break
                is OutboxState.FinTaken, is OutboxState.ResetHere, is OutboxState.Over -> return
            }
        }
        wake()
    }

    /** Whether the direction still takes bytes. */
    fun isOpen(): Boolean = state.load() is OutboxState.Open

    /** Suspends until the queue has room, or the direction is over, or [policy]'s deadline passes. */
    suspend fun awaitRoom(policy: WritePolicy): StreamRoom = await(OutboxWants.Room, deadlineOf(policy))

    /** Suspends until quiche took our FIN, or the direction is over, or [policy]'s deadline passes. */
    suspend fun awaitFinTaken(policy: WritePolicy): StreamRoom = await(OutboxWants.FinTaken, deadlineOf(policy))

    private suspend fun await(
        wants: OutboxWants,
        deadline: Duration,
    ): StreamRoom {
        when (val now = ready(wants)) {
            is OutboxReadiness.Now -> return now.room
            is OutboxReadiness.NotYet -> Unit
        }
        if (deadline == Duration.INFINITE) return park(wants)
        return withTimeoutOrNull(deadline) { park(wants) } ?: StreamRoom.DeadlinePassed
    }

    private suspend fun park(wants: OutboxWants): StreamRoom =
        suspendCancellableCoroutine { caller ->
            val waiting = OutboxParked.Waiting(caller, wants)
            parked.store(waiting)
            caller.invokeOnCancellation { parked.compareAndSet(waiting, OutboxParked.Nobody) }
            // The loop may have made room between the first look and the park: look again, and take the wake back if so.
            when (val now = ready(wants)) {
                is OutboxReadiness.Now -> if (parked.compareAndSet(waiting, OutboxParked.Nobody)) caller.resume(now.room)
                is OutboxReadiness.NotYet -> Unit
            }
        }

    /** What a wait for [wants] answers now, if it need not wait. */
    private fun ready(wants: OutboxWants): OutboxReadiness =
        readiness(state.load(), wants, OUTBOX_CAPACITY - (tail.load() - head.load()) >= OUTBOX_LOW_WATER)

    private fun memory(): OutboxRing.Held =
        when (val now = ring.load()) {
            is OutboxRing.Held -> now
            is OutboxRing.None -> ringOf(connection.bufferFactory, OUTBOX_CAPACITY).also { ring.store(it) }
        }

    private fun schedule() {
        if (scheduled.compareAndSet(OUTBOX_IDLE, OUTBOX_SCHEDULED)) connection.handToLoop(feed)
    }

    /** Any thread: answers the sender's wait if what it waits for is there. */
    private fun wake() {
        val now = parked.load()
        if (now !is OutboxParked.Waiting) return
        when (val answer = ready(now.wants)) {
            is OutboxReadiness.Now -> if (parked.compareAndSet(now, OutboxParked.Nobody)) now.caller.resume(answer.room)
            is OutboxReadiness.NotYet -> Unit
        }
    }

    // ---- the loop ----

    /** Inside: hands quiche what waits, in order, then our FIN when it was asked for; parks as the stream's writer when quiche can take nothing. */
    private inner class Feed :
        ConnWork(),
        Waiter {
        override fun run(inside: Inside) {
            scheduled.store(OUTBOX_IDLE)
            when (drain(inside.streams)) {
                Retry.Again -> inside.streams.entry(stream).parkWriter(this)
                Retry.Settled -> Unit
            }
        }

        override fun retry(table: StreamTable): Retry = drain(table)

        override fun connectionEnded(end: ConnEnd) = over(StreamSend.NotSent.ConnectionEnded(publicEndOf(end)))

        override fun gone(end: ConnEnd) = connectionEnded(end)

        private fun drain(table: StreamTable): Retry {
            when (state.load()) {
                is OutboxState.ResetHere, is OutboxState.Over, is OutboxState.FinTaken -> return letGo()
                is OutboxState.Open, is OutboxState.FinishAsked -> Unit
            }
            val send =
                when (val sender = table.sender()) {
                    is Streams.Open -> sender.send
                    is Sender.Refused -> {
                        over(StreamSend.NotSent.ConnectionEnded(publicEndOf(sender.why.end())))
                        return Retry.Settled
                    }
                }
            while (true) {
                val queued = tail.load()
                val sent = head.load()
                val waiting = (queued - sent).toInt()
                val finishing = state.load() is OutboxState.FinishAsked
                if (waiting == 0) {
                    if (!finishing) return Retry.Settled
                    val step = send.send(stream, 0L, 0, WriteEnd.Finish)
                    val code = step.code()
                    return when {
                        code >= 0 -> finTaken(table)
                        code == QUICHE_ERR_DONE -> blocked(table)
                        else -> refused(table, code, step.peer())
                    }
                }
                val memory =
                    when (val now = ring.load()) {
                        is OutboxRing.Held -> now
                        is OutboxRing.None -> return Retry.Settled
                    }
                val at = (sent % OUTBOX_CAPACITY).toInt()
                val length = minOf(waiting, OUTBOX_CAPACITY - at)
                val last = finishing && length == waiting
                val step = send.send(stream, memory.address + at, length, if (last) WriteEnd.Finish else WriteEnd.More)
                val code = step.code()
                when {
                    code > 0 -> {
                        head.store(sent + code)
                        wake()
                        listener.load().roomMade()
                        if (last && code == length) return finTaken(table)
                        if (code < length) return blocked(table)
                    }
                    code == 0 || code == QUICHE_ERR_DONE -> return blocked(table)
                    else -> return refused(table, code, step.peer())
                }
            }
        }

        private fun blocked(table: StreamTable): Retry {
            table.wantWritable(stream)
            return Retry.Again
        }

        private fun finTaken(table: StreamTable): Retry {
            state.store(OutboxState.FinTaken)
            table.sendEnded(stream, Side.Finished)
            wake()
            return letGo()
        }

        private fun refused(
            table: StreamTable,
            code: Int,
            peer: QuicAppErrorCode,
        ): Retry {
            // quiche answers a peer's STOP_SENDING as a stopped stream; any other refusal of an open stream's bytes is
            // the stream gone the same way, so both read as the peer stopping it.
            if (code == QUICHE_ERR_STREAM_STOPPED) table.stopped(stream)
            over(StreamSend.NotSent.PeerStopped(peer))
            return Retry.Settled
        }

        /** Nothing more goes: the queue's memory goes back to the connection's factory. */
        private fun letGo(): Retry {
            when (val now = ring.load()) {
                is OutboxRing.Held -> if (ring.compareAndSet(now, OutboxRing.None)) now.buffer.freeIfNeeded()
                is OutboxRing.None -> Unit
            }
            return Retry.Settled
        }
    }

    private fun over(why: StreamSend.NotSent.Over) {
        while (true) {
            val now = state.load()
            when (now) {
                is OutboxState.Open, is OutboxState.FinishAsked -> if (state.compareAndSet(now, OutboxState.Over(why))) break
                is OutboxState.FinTaken, is OutboxState.ResetHere, is OutboxState.Over -> return
            }
        }
        wake()
    }
}
