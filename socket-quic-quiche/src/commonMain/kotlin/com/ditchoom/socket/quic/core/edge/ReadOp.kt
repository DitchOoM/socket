package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.HeldTake
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.CallerSide
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.stream.InHand
import com.ditchoom.socket.quic.core.stream.ReadChunk
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.Side
import com.ditchoom.socket.quic.core.stream.StreamEntry
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlinx.coroutines.currentCoroutineContext
import kotlin.time.Duration

/**
 * The buffer a read holds until it hands it to its caller, already made into the answer the bytes will be:
 * [ReadAnswer.Data]; [None] once handed over or freed.
 */
internal sealed interface Spare {
    data object None : Spare
}

/** How many bytes a stream's next read asks for. Its reader's: a stream has one reader at a time. */
@CallerSide
internal class ReadSizing {
    var chunk: ReadChunk = ReadChunk.Initial
        private set

    /** A read that [asked] for its chunk got [got] bytes: the next read's size follows [ReadChunk.after]. */
    fun read(
        asked: ReadChunk,
        got: Int,
    ) {
        chunk = asked.after(asked.bytes, got)
    }
}

/**
 * Reads the next chunk, sized by [sizing], into a buffer the caller's thread takes from [idle] or [factory] when it makes
 * the read; the caller owns a returned buffer. First runs as work inside the connection, then waits parked on its
 * stream until data, its deadline, its caller's cancellation or the connection's end.
 *
 * Whoever runs the read, the loop included, allocates nothing: the buffer and its answer come with the read. The
 * connection lands the answer in the read's [Hand] and the caller makes the [ReadResult] on its own thread. The caller's
 * job is watched while it waits: a cancellation that comes first resumes the caller at once and hands the read back to
 * the connection, and a caller whose job is cancelled after the answer frees the buffer it never returned.
 *
 * Inside the connection only, except [answer] (the caller) and the job watch.
 */
@Confined
internal class ReadOp(
    private val stream: QuicStreamId,
    private val bound: Duration,
    private val deadlines: OpDeadlines,
    private val conn: Conn,
    private val edge: EdgeState,
    factory: BufferFactory,
    private val sizing: ReadSizing,
) : ConnWork(),
    Waiter,
    Bounded {
    private val idle = edge.reads
    private val asked = sizing.chunk
    private var spare: Spare = idle.take(asked.bytes, factory)
    private var lease: Lease = Lease.None
    private val hand = Hand<ReadAnswer>()

    // ---- the caller's side ----

    /**
     * The read's result once the connection answered it; a read answered while it ran in place does not suspend. The
     * caller's job is watched while the read waits.
     */
    suspend fun answer(): ReadResult {
        val context = currentCoroutineContext()
        val answer = hand.await(context) { cancelled() }
        try {
            if (answer is ReadAnswer.Data) sizing.read(asked, answer.buffer.remaining())
            return resultOf(answer, context)
        } finally {
            hand.taken()
            edge.returned(stream)
        }
    }

    /** Any thread: whether the caller still has to take bytes this read handed it. */
    override fun inHand(): InHand = if (hand.current() is ReadAnswer.Data) InHand.Yes else InHand.No

    /** The cancelling thread: a read not answered yet is answered Cancelled and handed back to the connection. */
    private fun cancelled() {
        when (hand.answer(ReadAnswer.Cancelled)) {
            Landed.First -> conn.submit(Withdraw(), CallerPlace.HandToLoop)
            Landed.Late -> Unit
        }
    }

    // ---- the connection's side, inside ----

    override fun run(inside: Inside) =
        guarded(::failed) {
            when (val state = inside.streams.state) {
                is Streams.Ended -> return connectionEnded(state.end)
                is Streams.Live -> Unit
            }
            if (hand.landed(ReadAnswer.Cancelled)) return release()
            if (inside.streams.isSpent(stream)) {
                release()
                settle(ReadAnswer.End)
                return
            }
            val entry = inside.streams.entry(stream)
            if (attempt(inside.streams, entry) == Retry.Again) {
                entry.parkReader(this)
                lease = deadlines.arm(this, bound, inside)
            } else {
                inside.streams.collect(entry)
            }
        }

    override fun retry(table: StreamTable): Retry {
        var answer = Retry.Settled
        guarded(::failed) {
            if (hand.landed(ReadAnswer.Cancelled)) {
                lease = deadlines.disarm(lease)
                return@guarded release()
            }
            answer = attempt(table, table.entry(stream))
            if (answer == Retry.Settled) lease = deadlines.disarm(lease)
        }
        return answer
    }

    /** The connection ended first: the read gets what the end held for its stream, or the close. */
    override fun connectionEnded(end: ConnEnd) {
        lease = deadlines.disarm(lease)
        release()
        settle(
            when (val held = edge.take(stream)) {
                is HeldTake.Bytes -> ReadAnswer.Data(held.buffer)
                is HeldTake.Finished -> ReadAnswer.End
                is HeldTake.Reset -> ReadAnswer.Reset
                is HeldTake.Closed -> ReadAnswer.Failed(Raised.ConnEnded(end), QuicCloseCircumstance.Ended)
            },
        )
    }

    override fun gone(end: ConnEnd) = connectionEnded(end)

    private fun attempt(
        table: StreamTable,
        entry: StreamEntry,
    ): Retry {
        if (entry.received is Side.Finished) {
            release()
            return settle(ReadAnswer.End)
        }
        // The read holds its buffer until it settles; one that settled holds none and is not run again.
        val into =
            when (val held = spare) {
                is Spare.None -> return Retry.Settled
                is ReadAnswer.Data -> held
            }
        val buffer = into.buffer
        val address =
            NativeBytes.addressing(buffer, { it }) {
                buffer.freeNativeMemory()
                spare = Spare.None
                return settle(ReadAnswer.Failed(Raised.NotNative(stream, buffer), QuicCloseCircumstance.Ended))
            }
        val room = buffer.limit()
        val answer =
            table.io.recv(stream, address, room).fold(
                more = { read -> if (read == 0) hold(into) else data(into, read) },
                last = { read ->
                    entry.finished()
                    if (read == 0) discard(ReadAnswer.End) else data(into, read)
                },
                nothingYet = { hold(into) },
                gone = {
                    buffer.freeNativeMemory()
                    settle(ReadAnswer.Failed(Raised.ConnEnded(ConnEnd.Released), QuicCloseCircumstance.Released))
                },
                reset = {
                    entry.receiveEnded(Side.Reset)
                    discard(ReadAnswer.Reset)
                },
                error = { code ->
                    buffer.freeNativeMemory()
                    settle(ReadAnswer.Failed(Raised.ReadRefused(stream, code), QuicCloseCircumstance.Ended))
                },
            )
        if (answer == Retry.Settled) spare = Spare.None
        return answer
    }

    /** Hands [read] bytes to the caller; until the caller takes them, an end counts the stream as unread. */
    private fun data(
        into: ReadAnswer.Data,
        read: Int,
    ): Retry {
        into.buffer.position(0)
        into.buffer.setLimit(read)
        when (hand.answer(into)) {
            Landed.First -> edge.handed.add(stream, this)
            Landed.Late -> into.buffer.freeIfNeeded()
        }
        return Retry.Settled
    }

    /** Parks on [into]; a retry that finds nothing again holds the same buffer and answer and allocates nothing. */
    private fun hold(into: ReadAnswer.Data): Retry {
        spare = into
        return Retry.Again
    }

    /** Ends the read with [result], its buffer unused. */
    private fun discard(result: ReadAnswer): Retry {
        release()
        return settle(result)
    }

    /** Hands [result] to the caller; a caller cancelled first never sees it, so the buffer of a [ReadAnswer.Data] is freed. */
    private fun settle(result: ReadAnswer): Retry {
        when (hand.answer(result)) {
            Landed.First -> Unit
            Landed.Late ->
                when (result) {
                    is ReadAnswer.Data -> result.buffer.freeIfNeeded()
                    is ReadAnswer.End, is ReadAnswer.Reset, is ReadAnswer.Failed, is ReadAnswer.Cancelled -> Unit
                }
        }
        return Retry.Settled
    }

    /** Lets go of the buffer the read holds, unused: the connection keeps it for its next read, since handing it back to a pool allocates. */
    private fun release() {
        when (val held = spare) {
            is Spare.None -> Unit
            is ReadAnswer.Data -> idle.keep(held)
        }
        spare = Spare.None
    }

    private fun failed(failure: Exception) {
        lease = deadlines.disarm(lease)
        release()
        settle(ReadAnswer.Failed(Raised.Thrown(failure), QuicCloseCircumstance.Ended))
    }

    private fun unpark(table: StreamTable) = table.unpark(stream, this)

    /** Inside: the bound passed while the read still waited parked. */
    fun expired(inside: Inside) {
        if (inside.streams.readerOf(stream) !== this) return
        lease = deadlines.disarm(lease)
        unpark(inside.streams)
        release()
        settle(ReadAnswer.Failed(OpFailure.DeadlinePassed(bound), QuicCloseCircumstance.Ended))
    }

    private inner class Withdraw : ConnWork() {
        override fun run(inside: Inside) {
            unpark(inside.streams)
            lease = deadlines.disarm(lease)
            release()
        }

        override fun gone(end: ConnEnd) = Unit
    }
}
