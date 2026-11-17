package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.Side
import com.ditchoom.socket.quic.core.stream.StreamEntry
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlinx.coroutines.currentCoroutineContext
import kotlin.time.Duration

/** The buffer a read that found nothing keeps for its retry, already made into the answer the bytes will be: [ReadAnswer.Data]. */
internal sealed interface Spare {
    data object None : Spare
}

/**
 * Reads the next chunk into a buffer from the caller's factory, sized by the stream's read chunk; the caller owns a
 * returned buffer. First runs as work inside the connection, then waits parked on its stream until data, its deadline,
 * its caller's cancellation or the connection's end.
 *
 * The connection lands the answer in the read's [Hand] and the caller makes the [ReadResult] on its own thread, so a
 * parked read is answered without allocating. The caller's job is watched while it waits: a cancellation that comes
 * first resumes the caller at once and hands the read back to the connection, and a caller whose job is cancelled after
 * the answer frees the buffer it never returned.
 *
 * Inside the connection only, except [answer] (the caller) and the job watch.
 */
@Confined
internal class ReadOp(
    private val stream: QuicStreamId,
    private val bound: Duration,
    private val deadlines: OpDeadlines,
    private val conn: Conn,
    private val factory: BufferFactory,
) : ConnWork(),
    Waiter,
    Bounded {
    private var spare: Spare = Spare.None
    private var lease: Lease = Lease.None
    private val hand = Hand<ReadAnswer>()

    // ---- the caller's side ----

    /**
     * The read's result once the connection answered it; a read answered while it ran in place does not suspend. The
     * caller's job is watched while the read waits.
     */
    suspend fun answer(): ReadResult {
        val context = currentCoroutineContext()
        return resultOf(hand.await(context) { cancelled() }, context)
    }

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
                is Streams.Open -> Unit
            }
            if (hand.landed(ReadAnswer.Cancelled)) return release()
            if (inside.streams.isSpent(stream)) {
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

    override fun connectionEnded(end: ConnEnd) {
        lease = deadlines.disarm(lease)
        release()
        settle(ReadAnswer.Failed(Raised.ConnEnded(end), QuicCloseCircumstance.Ended))
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
        // A retry keeps the buffer the park holds; only a settled read lets it go.
        val into =
            when (val kept = spare) {
                is Spare.None ->
                    ReadAnswer.Data(
                        table.idle.take(entry.chunk.bytes) { factory.allocate(entry.chunk.bytes) }.also {
                            // A pooled factory may return a larger buffer than asked for; the read asks for the chunk, not the buffer.
                            it.position(0)
                            it.setLimit(entry.chunk.bytes)
                        },
                    )
                is ReadAnswer.Data -> kept
            }
        val buffer = into.buffer
        val address =
            NativeBytes.addressing(buffer, { it }) {
                buffer.freeNativeMemory()
                spare = Spare.None
                return settle(ReadAnswer.Failed(Raised.NotNative(stream, buffer), QuicCloseCircumstance.Ended))
            }
        val asked = buffer.limit()
        val answer =
            table.io.recv(stream, address, asked).fold(
                more = { read -> if (read == 0) hold(into) else data(entry, into, asked, read) },
                last = { read ->
                    entry.finished()
                    if (read == 0) discard(table, into, ReadAnswer.End) else data(entry, into, asked, read)
                },
                nothingYet = { hold(into) },
                gone = {
                    buffer.freeNativeMemory()
                    settle(ReadAnswer.Failed(Raised.ConnEnded(ConnEnd.Released), QuicCloseCircumstance.Released))
                },
                reset = {
                    entry.receiveEnded(Side.Reset)
                    discard(table, into, ReadAnswer.Reset)
                },
                error = { code ->
                    buffer.freeNativeMemory()
                    settle(ReadAnswer.Failed(Raised.ReadRefused(stream, code), QuicCloseCircumstance.Ended))
                },
            )
        if (answer == Retry.Settled) spare = Spare.None
        return answer
    }

    private fun data(
        entry: StreamEntry,
        into: ReadAnswer.Data,
        asked: Int,
        read: Int,
    ): Retry {
        entry.read(asked, read)
        into.buffer.position(0)
        into.buffer.setLimit(read)
        return settle(into)
    }

    /** Parks on [into]; a retry that finds nothing again holds the same buffer and answer and allocates nothing. */
    private fun hold(into: ReadAnswer.Data): Retry {
        spare = into
        return Retry.Again
    }

    /** Ends the read with [result], keeping the buffer for the connection's next read: handing it back to a pool allocates. */
    private fun discard(
        table: StreamTable,
        into: ReadAnswer.Data,
        result: ReadAnswer,
    ): Retry {
        table.idle.keep(into.buffer)
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

    private fun release() {
        when (val kept = spare) {
            is Spare.None -> Unit
            is ReadAnswer.Data -> kept.buffer.freeNativeMemory()
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
