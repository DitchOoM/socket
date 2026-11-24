@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Queue
import com.ditchoom.socket.quic.core.stream.InHand
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.Side
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.Waiter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * The suspend reads of a stream, on the receiver's mechanism: a parked read takes a receiver's place. The loop reads what
 * quiche holds into the stream's one buffer ([ReadLend]) and lends it to the read, whose block runs on it; the loop
 * reads nothing while no read is parked, so the stream's flow-control window stays shut until the next read. A read
 * allocates no buffer, and the loop allocates nothing to answer it.
 */
@Queue
internal class ReadInbox(
    private val connection: CoreConnection,
    private val stream: QuicStreamId,
) : ConnWork(),
    Waiter,
    Bounded {
    private val buffer = ReadLend(connection, stream, this)

    /** Whether this is queued to run inside already: a work is queued once at a time. */
    private val scheduled = AtomicInt(0)

    /** Inside only: the deadline of the read [leaseFor] that parked, what was read for no read, and the stream's end. */
    private var lease: Lease = Lease.None
    private var leaseFor: ReadHold = ReadHold.Idle
    private var kept = 0
    private var end: ReadEnd = ReadEnd.Open

    // ---- the caller ----

    /** Parks until the stream's next bytes, its end, or [bound]; after the connection's end, what it held for the stream. */
    suspend fun next(
        bound: Duration,
        place: CallerPlace,
    ): ReadNext {
        when (val now = buffer.now()) {
            is ReadHold.Idle -> Unit
            is ReadHold.Waiting, is ReadHold.Lent, is ReadHold.Leaving, is ReadHold.LeavingLent, is ReadHold.Left,
            is ReadHold.Spent, is ReadHold.SpentLent,
            -> return buffer.notIdle(now)
        }
        // No cancellation handler is installed, as one makes every resume allocate: a cancelled caller withdraws here.
        var came = ReadReturn.Cancelled
        try {
            return parked(bound, place).also { came = ReadReturn.Answered }
        } finally {
            when (came) {
                ReadReturn.Cancelled -> buffer.withdrawn()
                ReadReturn.Answered -> Unit
            }
        }
    }

    private suspend fun parked(
        bound: Duration,
        place: CallerPlace,
    ): ReadNext =
        suspendCancellableCoroutine { caller ->
            when (val parked = buffer.park(ReadHold.Waiting(caller, bound))) {
                is ReadPark.Parked -> schedule(place)
                is ReadPark.Answered -> caller.resume(parked.next) { _, answer, _ -> answer.freeUntaken() }
            }
        }

    /** The read's block returned: the buffer is the loop's again. */
    fun returned() = buffer.returned()

    /** The stream reads no more (a stop, a receiver): a parked read meets the close, and the buffer goes back. */
    fun letGo() = buffer.letGo()

    /** Never handed to the connection: another read made the stream's inbox first. */
    fun discard() = buffer.discard()

    override fun inHand(): InHand = buffer.inHand()

    fun schedule(place: CallerPlace) {
        if (scheduled.compareAndSet(0, 1)) connection.submit(this, place)
    }

    // ---- inside ----

    override fun run(inside: Inside) {
        scheduled.store(0)
        guarded(::failed) {
            when (val state = inside.streams.state) {
                is Streams.Ended -> return connectionEnded(state.end)
                is Streams.Live -> Unit
            }
            when (val now = buffer.now()) {
                is ReadHold.Leaving -> return leave(inside.streams)
                is ReadHold.Waiting -> Unit
                is ReadHold.Idle, is ReadHold.Lent, is ReadHold.LeavingLent, is ReadHold.Left, is ReadHold.Spent,
                is ReadHold.SpentLent,
                -> {
                    // No read waits: the deadline of one that did is let go.
                    if (leaseFor !== now) letLeaseGo()
                    return
                }
            }
            when (end) {
                is ReadEnd.Open -> if (inside.streams.isSpent(stream)) ended(ScopedRead.End.PeerFinished)
                is ReadEnd.Ended -> Unit
            }
            when (val now = end) {
                is ReadEnd.Ended ->
                    if (kept == 0) {
                        answered(now.answer)
                        return
                    }
                is ReadEnd.Open -> Unit
            }
            val entry = inside.streams.entry(stream)
            when (drain(inside.streams)) {
                Retry.Again -> {
                    entry.parkReader(this)
                    when (val now = buffer.now()) {
                        is ReadHold.Waiting -> if (leaseFor !== now) arm(now, inside)
                        is ReadHold.Idle, is ReadHold.Lent, is ReadHold.Leaving, is ReadHold.LeavingLent, is ReadHold.Left,
                        is ReadHold.Spent, is ReadHold.SpentLent,
                        -> Unit
                    }
                }
                Retry.Settled -> inside.streams.collect(entry)
            }
        }
    }

    override fun retry(table: StreamTable): Retry {
        var answer = Retry.Settled
        guarded(::failed) {
            answer =
                when (buffer.now()) {
                    is ReadHold.Leaving -> {
                        letLeaseGo()
                        buffer.leave()
                        Retry.Settled
                    }
                    is ReadHold.Left, is ReadHold.Spent, is ReadHold.SpentLent -> Retry.Settled
                    is ReadHold.Idle, is ReadHold.Waiting, is ReadHold.Lent, is ReadHold.LeavingLent -> drain(table)
                }
        }
        return answer
    }

    /**
     * Answers a parked read with what quiche holds for the stream, or its end. With no read parked nothing is read; what
     * was read for a read cancelled as it landed is [kept] for the next.
     */
    private fun drain(table: StreamTable): Retry {
        val waiting =
            when (val now = buffer.now()) {
                is ReadHold.Waiting -> now
                is ReadHold.Idle, is ReadHold.Lent, is ReadHold.LeavingLent, is ReadHold.Leaving, is ReadHold.Left,
                is ReadHold.Spent, is ReadHold.SpentLent,
                -> return settledOr()
            }
        var length = kept
        while (length < ReadLend.LENT_ROOM && end is ReadEnd.Open) {
            val read =
                table.io.recv(stream, buffer.address + length, ReadLend.LENT_ROOM - length).fold(
                    more = { it },
                    last = { read ->
                        table.entry(stream).finished()
                        ended(ScopedRead.End.PeerFinished)
                        read
                    },
                    nothingYet = { 0 },
                    gone = {
                        ended(ScopedRead.End.ConnectionEnded(publicEndOf(ConnEnd.Released)))
                        0
                    },
                    reset = { code ->
                        table.entry(stream).receiveEnded(Side.Reset(code))
                        ended(ScopedRead.End.PeerReset(code))
                        0
                    },
                    error = { code ->
                        end = ReadEnd.Ended(ReadNext.Refused(Raised.ReadRefused(stream, code)))
                        0
                    },
                )
            length += read
            if (read == 0) break
        }
        if (length == 0) {
            return when (val now = end) {
                is ReadEnd.Ended -> answered(now.answer)
                is ReadEnd.Open -> Retry.Again
            }
        }
        letLeaseGo()
        return when (buffer.lend(waiting, length)) {
            Landed.First -> {
                kept = 0
                settledOr()
            }
            Landed.Late -> {
                // Its read was cancelled as they landed: the bytes are kept for the next read, the end after them.
                kept = length
                Retry.Again
            }
        }
    }

    /** Answers the parked read with [next], no bytes lent. */
    private fun answered(next: ReadNext): Retry {
        letLeaseGo()
        buffer.answer(next)
        return settledOr()
    }

    private fun ended(why: ScopedRead.End) {
        end =
            when (why) {
                is ScopedRead.End.PeerFinished -> FINISHED
                is ScopedRead.End.PeerReset, is ScopedRead.End.ConnectionEnded -> ReadEnd.Ended(ReadNext.Ended(why))
            }
    }

    /** Settled once the stream ended; its buffer goes back then, or when the block that has it returns. */
    private fun settledOr(): Retry =
        when (val now = end) {
            is ReadEnd.Open -> Retry.Again
            is ReadEnd.Ended -> {
                buffer.spent(now)
                Retry.Settled
            }
        }

    private fun arm(
        waiting: ReadHold.Waiting,
        inside: Inside,
    ) {
        letLeaseGo()
        leaseFor = waiting
        lease = connection.deadlines.arm(this, waiting.bound, inside)
    }

    private fun letLeaseGo() {
        lease = connection.deadlines.disarm(lease)
        leaseFor = ReadHold.Idle
    }

    /** Inside: the bound of the read that parked passed while it still waited. */
    fun expired(inside: Inside) {
        val now = buffer.now()
        if (now !== leaseFor) return
        letLeaseGo()
        buffer.answer(ReadNext.DeadlinePassed)
    }

    /** The stream let this go: the buffer goes back, and the stream's slot no longer waits on it. */
    private fun leave(table: StreamTable) {
        letLeaseGo()
        buffer.leave()
        table.unpark(stream, this)
    }

    private fun failed(failure: Exception) {
        end = ReadEnd.Ended(ReadNext.Refused(Raised.Thrown(failure)))
        answered(ReadNext.Refused(Raised.Thrown(failure)))
    }

    /** The connection ended: a parked read takes what the end held; the buffer goes back once no block has it. */
    override fun connectionEnded(end: ConnEnd) {
        letLeaseGo()
        buffer.connectionEnded()
    }

    override fun gone(end: ConnEnd) {
        scheduled.store(0)
        connectionEnded(end)
    }

    private companion object {
        /** The end most streams meet, made once. */
        val FINISHED = ReadEnd.Ended(ReadNext.Ended(ScopedRead.End.PeerFinished))
    }
}
