@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
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
import com.ditchoom.socket.quic.core.stream.Received
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.StreamEntry
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonDisposableHandle
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume

/** The buffer a read that found nothing keeps for its retry. */
private sealed interface Spare {
    data object None : Spare

    class Held(
        val buffer: PlatformBuffer,
    ) : Spare
}

/** Where a read stands between its caller and the connection: one atomic word, moved by compare-and-set. */
internal sealed interface ReadPhase {
    /** Not answered; the caller has not suspended. */
    data object Out : ReadPhase

    /** Not answered; the caller is suspended in [caller]. */
    class Waiting(
        val caller: Continuation<ReadAnswer>,
    ) : ReadPhase
}

/** What a read came to, set once; the caller turns it into its result on its own thread. */
internal sealed interface ReadAnswer : ReadPhase {
    /** Bytes arrived in the read's buffer, which is the caller's from here on. */
    data object Data : ReadAnswer

    data object End : ReadAnswer

    data object Reset : ReadAnswer

    class Failed(
        val failure: OpFailure,
        val during: QuicCloseCircumstance,
    ) : ReadAnswer

    /** The caller's job was cancelled first: the connection takes the read back and frees what it would have handed over. */
    data object Cancelled : ReadAnswer
}

/**
 * Reads the next chunk into a buffer from the caller's factory, sized by the stream's read chunk; the caller owns a
 * returned buffer. First runs as work inside the connection, then waits parked on its stream until data, its deadline,
 * its caller's cancellation or the connection's end.
 *
 * The connection publishes the answer and resumes the caller with it; the caller makes the [ReadResult] on its own
 * thread, so answering allocates nothing on the thread that answers. The caller's job is watched while it waits: a
 * cancellation that comes first resumes the caller at once and hands the read back to the connection, and a caller whose
 * job is cancelled after the answer frees the buffer it never returned.
 *
 * Inside the connection only, except [answer] (the caller), [invoke] (the cancelling thread) and [run] (the deadline's
 * thread).
 */
@Confined
internal class ReadOp(
    private val stream: QuicStreamId,
    private val deadline: OpDeadline,
    private val conn: Conn,
    private val factory: BufferFactory,
) : ConnWork(),
    Waiter,
    Runnable,
    (Throwable?) -> Unit {
    private var spare: Spare = Spare.None
    private val phase = AtomicReference<ReadPhase>(ReadPhase.Out)

    /** The buffer of a [ReadAnswer.Data]: written before that answer is published, read by the caller after it. */
    private lateinit var received: PlatformBuffer

    // ---- the caller's side ----

    /**
     * The read's result once the connection answered it, suspending until then. An answer that came while the read ran in
     * place costs no suspension: the waiting, and its continuation, are only made when the read parked.
     */
    suspend fun answer(): ReadResult =
        when (val now = phase.load()) {
            is ReadAnswer -> taken(now, currentCoroutineContext())
            is ReadPhase.Out, is ReadPhase.Waiting -> awaitAnswer()
        }

    /** Waits for the answer, watching the caller's job for a cancellation while it does. */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun awaitAnswer(): ReadResult {
        val context = currentCoroutineContext()
        val watch = context[Job]?.invokeOnCompletion(onCancelling = true, handler = this) ?: NonDisposableHandle
        val answer =
            try {
                suspendCoroutineUninterceptedOrReturn { caller -> park(caller.intercepted()) }
            } finally {
                watch.dispose()
            }
        return taken(answer, context)
    }

    /** Leaves [caller] for whoever answers, or returns the answer when it came first. */
    private fun park(caller: Continuation<ReadAnswer>): Any {
        val waiting = ReadPhase.Waiting(caller)
        while (true) {
            when (val now = phase.load()) {
                is ReadAnswer -> return now
                is ReadPhase.Out, is ReadPhase.Waiting -> if (phase.compareAndSet(ReadPhase.Out, waiting)) return COROUTINE_SUSPENDED
            }
        }
    }

    /** What [answer] gives a caller whose context is [context]; a cancelled caller frees the buffer and raises. */
    private fun taken(
        answer: ReadAnswer,
        context: CoroutineContext,
    ): ReadResult =
        when (answer) {
            is ReadAnswer.Data -> {
                val buffer = received
                try {
                    context.ensureActive()
                } catch (cancelled: CancellationException) {
                    buffer.freeIfNeeded()
                    throw cancelled
                }
                ReadResult.Data(buffer)
            }
            is ReadAnswer.End -> {
                context.ensureActive()
                ReadResult.End
            }
            is ReadAnswer.Reset -> {
                context.ensureActive()
                ReadResult.Reset
            }
            is ReadAnswer.Failed -> raiseFailure(answer.failure, answer.during)
            is ReadAnswer.Cancelled -> {
                // Only a cancelling job answers Cancelled, and a cancelling job is never active again.
                context.ensureActive()
                throw CancellationException(null as String?)
            }
        }

    /** The cancelling thread: the caller's job is being cancelled; a read not answered yet is answered Cancelled. */
    override fun invoke(cause: Throwable?) {
        while (true) {
            when (val now = phase.load()) {
                is ReadAnswer -> return
                is ReadPhase.Out -> if (phase.compareAndSet(now, ReadAnswer.Cancelled)) return withdraw()
                is ReadPhase.Waiting ->
                    if (phase.compareAndSet(now, ReadAnswer.Cancelled)) {
                        now.caller.resume(ReadAnswer.Cancelled)
                        return withdraw()
                    }
            }
        }
    }

    // ---- the connection's side, inside ----

    override fun run(inside: Inside) =
        guarded(::failed) {
            when (val state = inside.streams.state) {
                is Streams.Ended -> return connectionEnded(state.end)
                is Streams.Open -> Unit
            }
            if (callerLeft()) return release()
            val entry = inside.streams.entry(stream)
            if (attempt(inside.streams, entry) == Retry.Again) {
                entry.parkReader(this)
                deadline.arm(inside, this)
            }
        }

    override fun retry(table: StreamTable): Retry {
        var answer = Retry.Settled
        guarded(::failed) {
            if (callerLeft()) {
                deadline.disarm()
                return@guarded release()
            }
            answer = attempt(table, table.entry(stream))
            if (answer == Retry.Settled) deadline.disarm()
        }
        return answer
    }

    override fun connectionEnded(end: ConnEnd) {
        deadline.disarm()
        release()
        settle(ReadAnswer.Failed(Raised.ConnEnded(end), QuicCloseCircumstance.Ended))
    }

    override fun gone(end: ConnEnd) = connectionEnded(end)

    /** The deadline's thread: the deadline passed; the connection expires the read from inside. */
    override fun run() = conn.submit(Expire(), CallerPlace.HandToLoop)

    /** Hands the read back to the connection: its caller was cancelled before an answer. */
    private fun withdraw() = conn.submit(Withdraw(), CallerPlace.HandToLoop)

    private fun callerLeft(): Boolean = phase.load() is ReadAnswer.Cancelled

    private fun attempt(
        table: StreamTable,
        entry: StreamEntry,
    ): Retry {
        if (entry.received == Received.Finished) {
            release()
            return settle(ReadAnswer.End)
        }
        // A retry keeps the buffer the park holds; only a settled read lets it go.
        val buffer =
            when (val kept = spare) {
                is Spare.None ->
                    table.idle.take(entry.chunk.bytes) { factory.allocate(entry.chunk.bytes) }.also {
                        // A pooled factory may return a larger buffer than asked for; the read asks for the chunk, not the buffer.
                        it.position(0)
                        it.setLimit(entry.chunk.bytes)
                    }
                is Spare.Held -> kept.buffer
            }
        val address =
            NativeBytes.addressing(buffer, { it }) {
                buffer.freeNativeMemory()
                spare = Spare.None
                return settle(ReadAnswer.Failed(Raised.NotNative(stream, buffer), QuicCloseCircumstance.Ended))
            }
        val asked = buffer.limit()
        val answer =
            table.io.recv(stream, address, asked).fold(
                more = { read -> if (read == 0) hold(buffer) else data(entry, buffer, asked, read) },
                last = { read ->
                    entry.finished()
                    if (read == 0) discard(table, buffer, ReadAnswer.End) else data(entry, buffer, asked, read)
                },
                nothingYet = { hold(buffer) },
                gone = {
                    buffer.freeNativeMemory()
                    settle(ReadAnswer.Failed(Raised.ConnEnded(ConnEnd.Released), QuicCloseCircumstance.Released))
                },
                reset = { discard(table, buffer, ReadAnswer.Reset) },
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
        buffer: PlatformBuffer,
        asked: Int,
        read: Int,
    ): Retry {
        entry.read(asked, read)
        buffer.position(0)
        buffer.setLimit(read)
        received = buffer
        return settle(ReadAnswer.Data)
    }

    /** Parks on [buffer]; a retry that finds nothing again holds the same one and allocates nothing. */
    private fun hold(buffer: PlatformBuffer): Retry {
        if (spare is Spare.None) spare = Spare.Held(buffer)
        return Retry.Again
    }

    /** Ends the read with [result], keeping [buffer] for the connection's next read: handing it back to a pool allocates. */
    private fun discard(
        table: StreamTable,
        buffer: PlatformBuffer,
        result: ReadAnswer,
    ): Retry {
        table.idle.keep(buffer)
        return settle(result)
    }

    /**
     * Publishes [result] and resumes a waiting caller with it, allocating nothing; a caller cancelled first never sees
     * it, so the buffer of a [ReadAnswer.Data] is freed here.
     */
    private fun settle(result: ReadAnswer): Retry {
        while (true) {
            when (val now = phase.load()) {
                is ReadPhase.Out -> if (phase.compareAndSet(now, result)) return Retry.Settled
                is ReadPhase.Waiting ->
                    if (phase.compareAndSet(now, result)) {
                        now.caller.resume(result)
                        return Retry.Settled
                    }
                is ReadAnswer -> {
                    if (result is ReadAnswer.Data) received.freeIfNeeded()
                    return Retry.Settled
                }
            }
        }
    }

    private fun release() {
        when (val kept = spare) {
            is Spare.None -> Unit
            is Spare.Held -> kept.buffer.freeNativeMemory()
        }
        spare = Spare.None
    }

    private fun failed(failure: Exception) {
        deadline.disarm()
        release()
        settle(ReadAnswer.Failed(Raised.Thrown(failure), QuicCloseCircumstance.Ended))
    }

    private fun unpark(table: StreamTable) = table.entry(stream).unpark(this)

    private inner class Expire : ConnWork() {
        override fun run(inside: Inside) {
            if (inside.streams.entry(stream).reader !== this@ReadOp) return
            unpark(inside.streams)
            release()
            settle(ReadAnswer.Failed(OpFailure.DeadlinePassed(deadline.bound), QuicCloseCircumstance.Ended))
        }

        override fun gone(end: ConnEnd) = Unit
    }

    private inner class Withdraw : ConnWork() {
        override fun run(inside: Inside) {
            unpark(inside.streams)
            deadline.disarm()
            release()
        }

        override fun gone(end: ConnEnd) = Unit
    }
}
