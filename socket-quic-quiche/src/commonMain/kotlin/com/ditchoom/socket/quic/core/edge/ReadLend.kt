@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.HeldTake
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Queue
import com.ditchoom.socket.quic.core.stream.InHand
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume

/**
 * A stream's read buffer ([LENT_ROOM] bytes) and who has it ([ReadHold]): the loop while it is Idle or a read waits, a
 * read's block while it is Lent. Every move between them is one compare-and-set, and only the holder frees the buffer
 * once it is let go, so the loop never reads into freed memory and no block reads memory the loop freed. Any thread;
 * a move that needs the loop schedules [inbox].
 */
@Queue
internal class ReadLend(
    private val connection: CoreConnection,
    private val stream: QuicStreamId,
    private val inbox: ReadInbox,
) {
    // The connection keeps one idle read buffer: a stream takes it, and gives it back for the next stream to take.
    private val held = connection.edge.reads.take(LENT_ROOM, connection.bufferFactory)
    private val lent = held.buffer
    val address =
        when (val at = NativeBytes.addressOf(lent)) {
            is ReadAddress.At -> at.address
            is ReadAddress.NotAddressable -> {
                lent.freeNativeMemory()
                raise(Raised.FactoryNotNative(lent), QuicCloseCircumstance.Ended)
            }
        }
    private val bytes = ReadNext.Lent(lent)
    private val hold = AtomicReference<ReadHold>(ReadHold.Idle)

    fun now(): ReadHold = hold.load()

    // ---- the caller ----

    /** A read parks as [waiting]; or, the buffer not the loop's to lend, what it gets instead. */
    fun park(waiting: ReadHold.Waiting): ReadPark =
        if (hold.compareAndSet(ReadHold.Idle, waiting)) ReadPark.Parked else ReadPark.Answered(notIdle(hold.load()))

    /**
     * The read's caller was cancelled. Still waiting, the read withdraws and the loop lets its deadline go; cancelled as
     * bytes landed, it never runs its block and the buffer is the loop's again. A stream has one reader, so the wait or
     * the lent buffer is this caller's.
     */
    fun withdrawn() {
        while (true) {
            when (val now = hold.load()) {
                is ReadHold.Waiting -> if (hold.compareAndSet(now, ReadHold.Idle)) return inbox.schedule(CallerPlace.HandToLoop)
                is ReadHold.Lent, is ReadHold.LeavingLent, is ReadHold.SpentLent -> return returned()
                is ReadHold.Idle, is ReadHold.Leaving, is ReadHold.Left, is ReadHold.Spent -> return
            }
        }
    }

    /**
     * The read's block is done with [bytes]: the stream's own buffer is the loop's again, or goes back; bytes the
     * connection's end held for the stream ([ReadNext.Held]) are freed.
     */
    fun done(bytes: ReadBuffer) {
        if (bytes === lent) return returned()
        bytes.freeIfNeeded()
        connection.edge.returned(stream)
    }

    /** The read's block returned, or its caller never ran it: the buffer is the loop's again, or goes back. */
    fun returned() {
        while (true) {
            when (val now = hold.load()) {
                is ReadHold.Lent -> if (hold.compareAndSet(now, ReadHold.Idle)) break
                is ReadHold.LeavingLent ->
                    if (hold.compareAndSet(now, ReadHold.Leaving)) {
                        inbox.schedule(CallerPlace.HandToLoop)
                        break
                    }
                // The loop reads into the buffer no more once the stream ended: the block's return frees it here.
                is ReadHold.SpentLent -> if (free(now, now.spent)) break
                is ReadHold.Idle, is ReadHold.Waiting, is ReadHold.Leaving, is ReadHold.Left, is ReadHold.Spent -> break
            }
        }
        connection.edge.returned(stream)
    }

    /** The stream reads no more (a stop, a receiver): a parked read meets the close, and the buffer goes back. */
    fun letGo() {
        while (true) {
            when (val now = hold.load()) {
                is ReadHold.Idle -> if (hold.compareAndSet(now, ReadHold.Leaving)) break
                is ReadHold.Waiting ->
                    if (hold.compareAndSet(now, ReadHold.Leaving)) {
                        now.caller.resume(ReadNext.Closed)
                        break
                    }
                is ReadHold.Lent -> if (hold.compareAndSet(now, ReadHold.LeavingLent)) return
                is ReadHold.Leaving, is ReadHold.LeavingLent, is ReadHold.Left, is ReadHold.Spent, is ReadHold.SpentLent -> return
            }
        }
        inbox.schedule(CallerPlace.HandToLoop)
    }

    /** Never handed to the connection: another read made the stream's inbox first. */
    fun discard() = connection.edge.reads.keep(held)

    fun inHand(): InHand =
        when (hold.load()) {
            is ReadHold.Lent, is ReadHold.LeavingLent, is ReadHold.SpentLent -> InHand.Yes
            is ReadHold.Idle, is ReadHold.Waiting, is ReadHold.Leaving, is ReadHold.Left, is ReadHold.Spent -> InHand.No
        }

    /** What a read gets that finds the buffer not the loop's to lend. */
    fun notIdle(now: ReadHold): ReadNext =
        when (now) {
            is ReadHold.Spent -> now.answer
            is ReadHold.Left -> afterEnd()
            is ReadHold.Leaving, is ReadHold.LeavingLent -> ReadNext.Closed
            // Idle again since it looked: another read came and went meanwhile.
            is ReadHold.Waiting, is ReadHold.Lent, is ReadHold.SpentLent, is ReadHold.Idle -> ReadNext.Busy
        }

    /** What a read after the connection's end gets: what the end held for the stream, its end, or the close. */
    private fun afterEnd(): ReadNext =
        when (val held = connection.edge.take(stream)) {
            is HeldTake.Bytes -> ReadNext.Held(held.buffer)
            is HeldTake.Finished -> ReadNext.Ended(ScopedRead.End.PeerFinished)
            is HeldTake.Reset -> ReadNext.Ended(ScopedRead.End.PeerReset(held.code))
            is HeldTake.Closed ->
                ReadNext.Ended(
                    ScopedRead.End.ConnectionEnded(
                        when (val end = connection.end()) {
                            is EdgeEnd.Ended -> publicEndOf(end.end)
                            is EdgeEnd.Open -> publicEndOf(ConnEnd.Released)
                        },
                    ),
                )
        }

    // ---- the loop ----

    /** Lends the [length] bytes read for [waiting]; [Landed.Late] when its caller was cancelled first, keeping them. */
    fun lend(
        waiting: ReadHold.Waiting,
        length: Int,
    ): Landed {
        lent.position(0)
        lent.setLimit(length)
        if (!hold.compareAndSet(waiting, ReadHold.Lent)) return Landed.Late
        connection.edge.handed.add(stream, inbox)
        // Without a cancellation handler on the wait, this resume allocates nothing.
        waiting.caller.resume(bytes)
        return Landed.First
    }

    /** Answers the parked read with [next], no bytes lent: nothing to free if its caller was cancelled, so a plain resume. */
    fun answer(next: ReadNext) {
        when (val now = hold.load()) {
            is ReadHold.Waiting -> if (hold.compareAndSet(now, ReadHold.Idle)) now.caller.resume(next)
            is ReadHold.Idle, is ReadHold.Lent, is ReadHold.LeavingLent, is ReadHold.Leaving, is ReadHold.Left, is ReadHold.Spent,
            is ReadHold.SpentLent,
            -> Unit
        }
    }

    /** The stream met [end], which every read after answers: the buffer goes back now, or when the block that has it returns. */
    fun spent(end: ReadEnd.Ended) {
        while (true) {
            when (val now = hold.load()) {
                is ReadHold.Idle -> if (free(now, end.spent)) return
                is ReadHold.Lent -> if (hold.compareAndSet(now, end.spentLent)) return
                is ReadHold.Waiting, is ReadHold.Leaving, is ReadHold.LeavingLent, is ReadHold.Left, is ReadHold.Spent,
                is ReadHold.SpentLent,
                -> return
            }
        }
    }

    /** The stream let go of the buffer and no block has it: it goes back. */
    fun leave() {
        free(ReadHold.Leaving, ReadHold.Left)
    }

    /** The connection ended: a parked read takes what the end held; the buffer goes back once no block has it. */
    fun connectionEnded() {
        while (true) {
            when (val now = hold.load()) {
                is ReadHold.Idle, is ReadHold.Leaving -> if (free(now, ReadHold.Left)) return
                is ReadHold.Waiting ->
                    if (free(now, ReadHold.Left)) {
                        now.caller.resume(afterEnd()) { _, answer, _ -> answer.freeUntaken() }
                        return
                    }
                is ReadHold.Lent -> if (hold.compareAndSet(now, ReadHold.LeavingLent)) return
                is ReadHold.LeavingLent, is ReadHold.Left, is ReadHold.Spent, is ReadHold.SpentLent -> return
            }
        }
    }

    /** Moves [from] to [to], a state in which nobody has the buffer, and frees it; false when [from] moved first. */
    private fun free(
        from: ReadHold,
        to: ReadHold,
    ): Boolean {
        if (!hold.compareAndSet(from, to)) return false
        connection.edge.reads.keep(held)
        return true
    }

    companion object {
        const val LENT_ROOM = 65_536
    }
}
