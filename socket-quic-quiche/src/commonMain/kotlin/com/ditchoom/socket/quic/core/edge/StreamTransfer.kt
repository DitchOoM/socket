package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamSide
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.Waiter
import kotlinx.coroutines.CompletionHandler
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonDisposableHandle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.time.Duration

/**
 * One direction of one typed stream: a single operation, made once and reused for every message, that moves bytes
 * between quiche and one native buffer ([Inbound]'s accumulator to receive into, [Outbound]'s scratch to send from). The
 * buffer belongs to one side at a time, and the hand-over is one atomic word, the [PhaseCell]:
 *
 * - a caller [acquire]s the buffer, works in it, and [arm]s the operation, which hands it to the connection;
 * - the connection runs the operation inside, in place on the caller's thread or on the loop's, and settles it, which
 *   hands the buffer back and resumes the caller, which has nothing to wait for when the operation already ran;
 * - a caller cancelled while the operation is out abandons it and is resumed at once: the connection keeps the buffer
 *   until it has taken the operation back, and the next caller waits for that, suspended, never blocked.
 *
 * Closing the stream frees the buffer from inside the connection, after every operation that could still touch it.
 */
@Confined
internal class StreamTransfer(
    private val stream: QuicStreamId,
    private val bound: Duration,
    private val deadlines: OpDeadlines,
    private val conn: Conn,
    private val lane: Lane,
) : ConnWork(),
    Waiter,
    Bounded {
    private val phase = PhaseCell()
    private val waiting = WaiterCell()
    private var outcome: Outcome = Outcome.Ok
    private var lease: Lease = Lease.None
    private val onCancel: CompletionHandler = { _ -> abandon() }
    private val withdraw = Withdraw()
    private val release = Release()

    private val side: QuicStreamSide =
        when (lane) {
            is Inbound -> QuicStreamSide.Read
            is Outbound -> QuicStreamSide.Write
        }

    // ---- the caller's side ----

    /** Takes the buffer for the caller, or says it has to wait for the connection to give it back. */
    fun acquire(): Acquired {
        while (true) {
            when (val now = phase.load()) {
                // A finished operation whose caller was cancelled before it took the result: its result is not wanted.
                is Phase.Idle, is Phase.Done -> if (phase.moves(now, Phase.Using)) return Acquired.Granted
                is Phase.Abandoned -> return Acquired.Settling
                is Phase.Using, is Phase.Armed -> raise(Raised.Typed(stream, TypedFailure.Busy(side)), QuicCloseCircumstance.Ended)
                is Phase.Closing, is Phase.Freed -> raise(Raised.StreamClosed(stream, side), QuicCloseCircumstance.Ended)
            }
        }
    }

    /** Suspends until the connection has taken back the operation a cancelled call left out. */
    suspend fun awaitSettled() {
        // A resume can belong to an earlier wait whose settling thread was slow; the phase is what says it is over.
        while (phase.load() is Phase.Abandoned) {
            suspendCancellableCoroutine<Unit> { cont ->
                waiting.leave(cont)
                if (phase.load() !is Phase.Abandoned) waiting.resumeSelf(cont)
            }
        }
    }

    /** Hands the buffer to the connection and queues the operation: it runs inside, in place when [place] allows. */
    fun arm(place: CallerPlace) {
        if (!phase.moves(Phase.Using, Phase.Armed)) {
            freeBuffer()
            phase.store(Phase.Freed)
            raise(Raised.StreamClosed(stream, side), QuicCloseCircumstance.Ended)
        }
        conn.submit(this, place)
    }

    /** Whether the operation already settled, so the caller has nothing to wait for. */
    fun isDone(): Boolean = phase.load() is Phase.Done

    /** Suspends until the operation settles; settling resumes the caller's own continuation and allocates nothing. */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun awaitDone() {
        val context = currentCoroutineContext()
        // A settling thread that is slow to resume its waiter can resume the next operation's: the phase says it is over.
        while (phase.load() is Phase.Armed) {
            val watch = context[Job]?.invokeOnCompletion(onCancelling = true, handler = onCancel) ?: NonDisposableHandle
            try {
                suspendCoroutineUninterceptedOrReturn { caller -> waiting.park(caller) { phase.load() !is Phase.Armed } }
            } finally {
                watch.dispose()
            }
            if (!context.isActive) raiseCancelled(context)
        }
    }

    /** Takes the buffer back after a settled operation; a failure leaves the buffer and is raised from here. */
    fun taken() {
        val result = outcome
        if (!phase.moves(Phase.Done, Phase.Using)) raise(Raised.StreamClosed(stream, side), QuicCloseCircumstance.Ended)
        when (result) {
            is Outcome.Ok -> Unit
            is Outcome.Failed -> {
                leave()
                raiseFailure(result.failure, QuicCloseCircumstance.Ended)
            }
        }
    }

    /** The caller is done with the buffer: at rest again, or freed when the stream was closed meanwhile. */
    fun leave() {
        if (!phase.moves(Phase.Using, Phase.Idle)) {
            freeBuffer()
            phase.store(Phase.Freed)
        }
    }

    /** The work that closes this transfer: frees its buffer from inside the connection. */
    fun closing(): ConnWork = release

    // ---- the connection's side, inside ----

    override fun run(inside: Inside) =
        guarded(::failed) {
            when (val state = inside.streams.state) {
                is Streams.Ended -> return connectionEnded(state.end)
                is Streams.Live -> Unit
            }
            // The caller left before the operation ran: the withdraw takes it back.
            if (phase.load() !is Phase.Armed) return
            if (attempt(inside.streams) == Retry.Again) {
                park(inside.streams)
                lease = deadlines.arm(this, bound, inside)
            } else {
                inside.streams.collect(stream)
            }
        }

    override fun retry(table: StreamTable): Retry {
        if (phase.load() !is Phase.Armed) {
            lease = deadlines.disarm(lease)
            return Retry.Settled
        }
        var answer = Retry.Settled
        guarded(::failed) { answer = attempt(table) }
        return answer
    }

    override fun connectionEnded(end: ConnEnd) {
        settle(Outcome.Failed(Raised.ConnEnded(end)))
    }

    override fun gone(end: ConnEnd) = connectionEnded(end)

    private fun attempt(table: StreamTable): Retry =
        when (val moved = moveBytes(table)) {
            is Moved.Done -> settle(Outcome.Ok)
            is Moved.Again -> Retry.Again
            is Moved.Refused -> settle(Outcome.Failed(moved.failure))
        }

    private fun moveBytes(table: StreamTable): Moved =
        when (lane) {
            is Inbound -> lane.attempt(table, stream)
            is Outbound -> lane.attempt(table, stream)
        }

    private fun park(table: StreamTable) {
        val entry = table.entry(stream)
        when (lane) {
            is Inbound -> entry.parkReader(this)
            is Outbound -> entry.parkWriter(this)
        }
    }

    private fun takenBack(inside: Inside) {
        when (lane) {
            is Inbound -> Unit
            is Outbound -> lane.abandoned(inside, stream)
        }
    }

    private fun freeBuffer() {
        when (lane) {
            is Inbound -> lane.into.freeStorage()
            is Outbound -> lane.from.freeStorage()
        }
    }

    private fun settle(result: Outcome): Retry {
        endParking()
        outcome = result
        if (phase.moves(Phase.Armed, Phase.Done)) waiting.resumeWaiting()
        return Retry.Settled
    }

    /** The parking is over: its deadline slot goes back, and an expiry already handed over finds it stale. */
    private fun endParking() {
        lease = deadlines.disarm(lease)
    }

    private fun failed(failure: Exception) {
        settle(Outcome.Failed(Raised.Thrown(failure)))
    }

    /** The caller's call was cancelled while the operation was out. */
    private fun abandon() {
        if (phase.moves(Phase.Armed, Phase.Abandoned)) {
            conn.submit(withdraw, CallerPlace.HandToLoop)
            waiting.resumeWaiting()
        }
    }

    private fun unpark(table: StreamTable) = table.unpark(stream, this)

    /** Inside: takes back an abandoned operation and tells whoever waits for that. */
    private inner class Withdraw : ConnWork() {
        override fun run(inside: Inside) {
            unpark(inside.streams)
            endParking()
            takenBack(inside)
            acknowledge()
        }

        override fun gone(end: ConnEnd) = acknowledge()

        private fun acknowledge() {
            if (phase.moves(Phase.Abandoned, Phase.Idle)) waiting.resumeWaiting()
        }
    }

    /** Inside: the bound passed while the operation still waited parked. */
    fun expired(inside: Inside) {
        if (phase.load() !is Phase.Armed) return
        unpark(inside.streams)
        takenBack(inside)
        settle(Outcome.Failed(OpFailure.DeadlinePassed(bound)))
    }

    /** Inside: frees the buffer once no caller or operation can touch it. */
    private inner class Release : ConnWork() {
        override fun run(inside: Inside) = freeing { unpark(inside.streams) }

        override fun gone(end: ConnEnd) = freeing { }

        private fun freeing(unparkIt: () -> Unit) {
            while (true) {
                when (val now = phase.load()) {
                    is Phase.Idle, is Phase.Done -> if (phase.moves(now, Phase.Freed)) return freeBuffer()
                    is Phase.Using -> if (phase.moves(now, Phase.Closing)) return
                    is Phase.Armed -> {
                        unparkIt()
                        settle(Outcome.Failed(Raised.StreamClosed(stream, side)))
                    }
                    is Phase.Abandoned ->
                        if (phase.moves(now, Phase.Freed)) {
                            unparkIt()
                            lease = deadlines.disarm(lease)
                            freeBuffer()
                            return waiting.resumeWaiting()
                        }
                    is Phase.Closing, is Phase.Freed -> return
                }
            }
        }
    }
}
