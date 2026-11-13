@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference

/**
 * Per-stream state managed by the [QuicheDriver].
 *
 * The [dataSignal] channel wakes pending reads when quiche reports data available.
 * CONFLATED: multiple signals before a receive coalesce into one wakeup.
 */
class StreamSlot internal constructor(
    val id: QuicStreamId,
    initial: StreamLifecycle,
) {
    constructor(id: QuicStreamId) : this(id, StreamLifecycle.Open)

    private val lifecycleState = AtomicReference(initial)

    /** What of this stream is still to happen. See [StreamLifecycle]. */
    internal val lifecycle: StreamLifecycle get() = lifecycleState.load()

    /**
     * [direction] is over for good. Returns true for the one call that made the stream [StreamLifecycle.Finished]:
     * from then on nothing the driver could do for this stream is left, and it releases the slot.
     */
    internal fun finish(direction: StreamDirection): Boolean {
        while (true) {
            val current = lifecycleState.load()
            val next = current.after(direction)
            if (next === current) return false
            if (lifecycleState.compareAndSet(current, next)) return next === StreamLifecycle.Finished
        }
    }

    /** A stream whose receive side is over from the start is a unidirectional one this endpoint opened. */
    private val receiveSide: StreamReceiveSide =
        when (initial) {
            StreamLifecycle.ReceiveOver, StreamLifecycle.Finished -> StreamReceiveSide.Never
            StreamLifecycle.Open, StreamLifecycle.SendOver -> StreamReceiveSide.FromPeer
        }

    val dataSignal = Channel<Unit>(Channel.CONFLATED)

    /**
     * Wakes a pending *write* when quiche reports the stream's flow-control window has reopened
     * (it appears in `quiche_conn_writable`). The write-path mirror of [dataSignal]: a writer that
     * gets `QUICHE_ERR_DONE` (window full, 0 bytes accepted) parks here instead of delay-polling, and
     * the driver signals it from `signalWritableStreams()` once a `MAX_STREAM_DATA` / `MAX_DATA` frame
     * reopens the window. CONFLATED: multiple signals before the writer wakes coalesce into one, and a
     * signal sent *after* the driver-computed `DONE` but before the writer parks is buffered — so there
     * is no lost-wakeup window (same guarantee [dataSignal] relies on).
     */
    val writableSignal = Channel<Unit>(Channel.CONFLATED)

    /**
     * Set once quiche reports a terminal event for the stream's read side — even when that event
     * arrived *coalesced with the last data chunk* (`stream_recv` → bytes > 0 **and** fin/reset =
     * true). That data chunk is returned to the caller as
     * [com.ditchoom.buffer.flow.ReadResult.Data], so the terminal event itself can't be returned in
     * the same `read()`; this field carries it to the next `read()`. Without it, the reader would
     * park on [dataSignal] forever — quiche has already delivered the terminal event, so no further
     * data or readable-signal is coming.
     *
     * [StreamEnd.Fin] means the next `read()` returns [com.ditchoom.buffer.flow.ReadResult.End].
     * [StreamEnd.Reset] carries the peer's RESET_STREAM application error code to that same next
     * `read()`.
     *
     * Latched only through [latchFin] / [latchReset]: the driver loop and the reading coroutine both
     * latch it, from different threads, and the first verdict wins.
     */
    val end: StreamEnd get() = latchedEnd.load()

    private val latchedEnd = AtomicReference<StreamEnd>(StreamEnd.Open)

    /**
     * Latch [StreamEnd.Fin], unless a verdict is already latched. A compare-and-set, not a
     * check-then-write: two threads latch this, and a read-then-write lets the second writer overwrite
     * the first verdict (a Reset replaced by a Fin) after both saw [StreamEnd.Open].
     */
    internal fun latchFin(): Boolean = latchedEnd.compareAndSet(StreamEnd.Open, StreamEnd.Fin)

    /** Latch the peer's RESET_STREAM with its [code], unless a verdict is already latched. See [latchFin]. */
    internal fun latchReset(code: QuicAppErrorCode): Boolean = latchedEnd.compareAndSet(StreamEnd.Open, StreamEnd.Reset(code))

    /**
     * Stream bytes quiche had already delivered to us that no reader has taken yet. Three producers:
     * [QuicheDriver.drainReadableStreamsIntoSlots] at connection teardown,
     * [DriverStreamAdapter.salvageCancelledRecv] when a read's timeout or cancellation unwound it before
     * the driver answered its still-queued `StreamRecv`, and the driver handing a read parked in
     * [awaitData] the bytes it was waiting for (see [takeWaitingRead]).
     *
     * The connection dying does not un-receive them (RFC 9000 §10.2: a CONNECTION_CLOSE ends the
     * connection; the stream data the transport already accepted and acknowledged is still the
     * application's). Without this queue those bytes would die with `quiche_conn_free` and the pending
     * `read()` would return [com.ditchoom.buffer.flow.ReadResult.End] — indistinguishable from a clean
     * FIN.
     *
     * UNLIMITED so the drain — which runs on the driver loop, where suspending is not an option — can
     * never block, and because quiche's own flow-control window already bounds how much there can be.
     * A [Channel] rather than a plain deque because producer (driver loop) and consumer (the reading
     * coroutine) are different threads. Ownership of a buffer taken from here transfers to the reader,
     * exactly like [QuicheStreamAdapter.streamRead]'s data path; anything still queued when the stream
     * is closed is released by [DriverStreamAdapter.releaseUndeliveredReads].
     */
    val pendingData = Channel<ReadBuffer>(Channel.UNLIMITED)

    /**
     * The size of the read parked on [dataSignal], or 0 when no read is parked there. Set by the reader
     * just before it parks ([awaitData]) and taken by the driver loop ([takeWaitingRead]) when quiche
     * reports the stream readable, so the driver can hand the bytes over itself instead of only waking
     * the reader to come and ask for them (one round trip through the loop fewer per read that waited).
     */
    private val waitingRead = AtomicInt(0)

    /**
     * Park the reader until the driver wakes [dataSignal], advertising a read of [size] bytes for the
     * driver to fill ([takeWaitingRead]) meanwhile. The advert is withdrawn BEFORE this returns, so the
     * caller's next look at [pendingData] cannot be followed by a drain it did not see: once the advert
     * is gone the driver only announces. A drain that took the advert first may still be landing as the
     * reader looks; the driver answers that reader's next `StreamRecv` with `Done` while the queue holds
     * anything or the end is latched (see `QuicheDriver.execute`), so the queued chunk is read before any
     * byte quiche has after it, and the end of a stream quiche has already collected is the reader's
     * verdict rather than a question put to quiche.
     *
     * A cancelled wait leaves the stream [ReaderMay.Ask]: the wake it was cancelled under may have been
     * consumed with it.
     */
    internal suspend fun awaitData(size: Int) {
        advertiseWaitingRead(size)
        try {
            dataSignal.receive()
        } catch (cancelled: CancellationException) {
            assumption.store(ReaderMay.Ask)
            throw cancelled
        } finally {
            withdrawWaitingRead()
        }
    }

    /**
     * What the next read of this stream may assume about what quiche holds for it. One value, decided by
     * the loop's sweep (after each turn the loop looks at the readable streams) and by the read itself.
     */
    private val assumption = AtomicReference<ReaderMay>(ReaderMay.Park)

    /**
     * Loop only: quiche holds bytes, a FIN or a reset for this stream and no read is parked to take them.
     * The next read asks; [dataSignal] wakes a read that is about to park.
     */
    internal fun announce() {
        assumption.store(ReaderMay.Ask)
        dataSignal.trySend(Unit)
    }

    /**
     * Loop only: wakes the read parked in [awaitData] after the loop took [chunk] out of quiche for it
     * ([deliverDrained]). A chunk that filled the [requested] size may be followed by more, so the stream
     * stays [ReaderMay.Ask]. Returns false when the queue is closed and [chunk] was not taken.
     */
    internal fun handedOff(
        chunk: ReadBuffer,
        fin: Boolean,
        requested: Int,
        bytesRead: Int,
    ): Boolean {
        val taken = deliverDrained(chunk, fin)
        if (bytesRead >= requested) assumption.store(ReaderMay.Ask)
        dataSignal.trySend(Unit)
        return taken
    }

    /**
     * Whether a read that found nothing queued must put a `StreamRecv` to the loop before it parks.
     *
     * quiche's readable set is level-triggered (a stream stays in it until drained) and grows only when a
     * datagram is received; the loop sweeps it at the end of every turn. So bytes, a FIN or a reset waiting
     * in quiche are, after the turn that received them, handed to a read parked in [awaitData] or announced
     * ([announce]) until read. A read that finds the stream [ReaderMay.Park] therefore parks at once:
     * whatever arrives later reaches it through the same sweep. Taking the state resets it; a wake left in
     * [dataSignal] is drained first, so it cannot wake the park that follows.
     *
     * A stream quiche can only answer with a typed error (see [StreamReceiveSide.Never]), or whose read side
     * the application has finished, always asks.
     */
    internal fun readNeedsQuicheAnswer(): Boolean {
        dataSignal.tryReceive()
        val ask = assumption.exchange(ReaderMay.Park) === ReaderMay.Ask
        return ask || receiveSide === StreamReceiveSide.Never || readState.load() == StreamReadState.Finished
    }

    /** A read is about to ask the loop, which drains everything quiche holds: whatever was announced is answered. */
    internal fun asking() = assumption.store(ReaderMay.Park)

    /** [awaitData]'s advert, apart from its wait: the hand-off model checks these two around the driver. */
    internal fun advertiseWaitingRead(size: Int) = waitingRead.store(size)

    internal fun withdrawWaitingRead() = waitingRead.store(0)

    /**
     * Driver loop only: the size of the read parked on [dataSignal], claiming it so at most one chunk is
     * drained per park; 0 when no read is parked.
     */
    internal fun takeWaitingRead(): Int = waitingRead.exchange(0)

    /** Where the application's reads of this stream stand. See [StreamReadState]; advanced by CAS only. */
    val readState = AtomicReference<StreamReadState>(StreamReadState.Idle)

    /**
     * The driver hands over [chunk] (bytes quiche already accepted, or null for none) and then, when
     * [fin] is set, latches [StreamEnd.Fin]. In that order: a reader that sees the FIN must also see
     * the chunk it follows — see [queuedOrLatchedVerdict] for the reader's half of the ordering.
     *
     * @return false when the queue is closed and [chunk] was not taken; the caller still owns it, and
     *   no FIN is latched.
     */
    internal fun deliverDrained(
        chunk: ReadBuffer?,
        fin: Boolean,
    ): Boolean {
        if (chunk != null && pendingData.trySend(chunk).isFailure) return false
        if (fin) latchFin()
        return true
    }

    /**
     * A read takes the oldest chunk in [pendingData], or null when there is none. The stream is marked
     * [StreamReadState.HandedOff] *before* the take, so a chunk is never out of the queue while the
     * stream claims nothing — [holdsUnread] would read false over bytes a read is holding. When the
     * queue was empty, [handBack] gives back the hand-off this call made itself (and only that one).
     */
    internal inline fun takeQueued(handBack: () -> Unit): ReadBuffer? {
        val marked = readState.compareAndSet(StreamReadState.Idle, StreamReadState.HandedOff)
        val chunk = pendingData.tryReceive().getOrNull()
        if (chunk == null && marked) handBack()
        return chunk
    }

    /**
     * What a read returns when quiche has nothing more for it right now: the next queued chunk
     * (through [take]), else the latched verdict, else null — the read waits for more.
     *
     * [end] is read BEFORE the queue. [deliverDrained] queues before it latches, so a FIN seen here
     * means every chunk it follows is already visible to [take]. The other order has a window: the
     * queue looks empty, the driver's teardown drain queues the stream's last chunk and latches its
     * FIN, and the read reports End over bytes the stream is still holding — the #318 data loss, by a
     * race instead of a missing drain.
     */
    internal inline fun queuedOrLatchedVerdict(take: () -> ReadResult.Data?): ReadResult? =
        when (end) {
            StreamEnd.Open -> take()
            StreamEnd.Fin -> take() ?: ReadResult.End
            is StreamEnd.Reset -> take() ?: ReadResult.Reset
        }

    /**
     * Whether the driver answers this stream's `StreamRecv` with `Done` instead of asking quiche: bytes
     * the loop already handed over wait in [pendingData] and come before anything quiche still holds, and
     * a latched [end] means quiche reported the stream's end and has collected the stream.
     */
    internal fun answersStreamRecvWithDone(): Boolean = !pendingData.isEmpty || end != StreamEnd.Open

    /** A read returned bytes to its caller: [StreamReadState.HandedOff] back to [StreamReadState.Idle], never over [StreamReadState.Finished]. */
    internal fun returnHandOff() {
        readState.compareAndSet(StreamReadState.HandedOff, StreamReadState.Idle)
    }

    /** The application is done reading this stream for good. */
    internal fun finishReads() {
        readState.store(StreamReadState.Finished)
    }

    /**
     * Whether this stream still holds something for the application: bytes handed to a read that has not
     * returned them, bytes queued in [pendingData], or a FIN/RESET no read has reported yet.
     */
    fun holdsUnread(): Boolean =
        when (readState.load()) {
            StreamReadState.Finished -> false
            StreamReadState.HandedOff -> true
            StreamReadState.Idle -> !pendingData.isEmpty || end != StreamEnd.Open
        }
}

/**
 * The application's reads of one stream.
 *
 * [Idle] between reads. [HandedOff] from the moment bytes or a terminal verdict are handed to a read —
 * the driver answering its `StreamRecv`, or the read taking a chunk off [StreamSlot.pendingData] — until
 * that read returns them to its caller, when it goes back to [Idle]. [Finished], for good, once a read
 * returns the stream's terminal verdict (End, Reset, the connection's close, a stream read error) or the
 * application releases the read side. A closed connection is waiting on every stream that
 * [holds unread][StreamSlot.holdsUnread] data (see [com.ditchoom.socket.quic.QuicUnreadAtClose]).
 */
sealed interface StreamReadState {
    data object Idle : StreamReadState

    data object HandedOff : StreamReadState

    data object Finished : StreamReadState
}

/**
 * How the read side of a stream ends. [Open] until quiche reports a terminal event — a clean FIN or
 * a peer RESET_STREAM — at which point [StreamSlot.end] latches to [Fin] or [Reset] and stays there;
 * a stream never returns to [Open].
 */
sealed interface StreamEnd {
    data object Open : StreamEnd

    data object Fin : StreamEnd

    /** Peer RESET_STREAM (RFC 9000 §19.4); [applicationErrorCode] is the peer's code. */
    data class Reset(
        val applicationErrorCode: QuicAppErrorCode,
    ) : StreamEnd
}

/** The two directions of a stream. */
internal enum class StreamDirection { Send, Receive }

/**
 * What of a stream is still to happen, from the driver's side. A direction is over when this endpoint can
 * neither do nor be told anything more in it: the send side once the FIN is accepted by quiche, a
 * RESET_STREAM is sent, or the peer's STOP_SENDING arrives; the receive side once the application has
 * been given the peer's FIN or RESET_STREAM, or has sent STOP_SENDING. A direction the stream does not
 * have (the receive side of a unidirectional stream this endpoint opened, the send side of one the peer
 * opened) is over from the start.
 *
 * [Finished] is absorbing, and is the only state in which the driver drops its slot: quiche collects a
 * stream in the same condition, and its identifier is never again a stream to announce (see
 * [ReleasedPeerStreams]).
 */
internal sealed interface StreamLifecycle {
    /** Both directions are live. */
    data object Open : StreamLifecycle

    /** The send side is over; the receive side is live. */
    data object SendOver : StreamLifecycle

    /** The receive side is over; the send side is live. */
    data object ReceiveOver : StreamLifecycle

    /** Both directions are over. */
    data object Finished : StreamLifecycle

    /** This lifecycle once [direction] is over. */
    fun after(direction: StreamDirection): StreamLifecycle =
        when (direction) {
            StreamDirection.Send ->
                when (this) {
                    Open, SendOver -> SendOver
                    ReceiveOver, Finished -> Finished
                }
            StreamDirection.Receive ->
                when (this) {
                    Open, ReceiveOver -> ReceiveOver
                    SendOver, Finished -> Finished
                }
        }

    companion object {
        /** The lifecycle of stream [id] when an endpoint of [role] first has it. */
        fun of(
            id: QuicStreamId,
            role: QuicRole,
        ): StreamLifecycle {
            if (id.isBidirectional) return Open
            val openedHere =
                when (role) {
                    QuicRole.Client -> id.isClientInitiated
                    QuicRole.Server -> id.isServerInitiated
                }
            return if (openedHere) ReceiveOver else SendOver
        }
    }
}

/** What the next read of a stream may assume about what quiche holds for it. See [StreamSlot.readNeedsQuicheAnswer]. */
internal sealed interface ReaderMay {
    /** Nothing quiche holds is unannounced: the read parks without asking the loop. */
    data object Park : ReaderMay

    /** quiche may hold bytes, a FIN or a reset: the read asks the loop first. */
    data object Ask : ReaderMay
}

/**
 * Whether bytes can ever arrive on a stream's read side, decided from the stream's direction and who opened
 * it when the slot is made.
 */
internal sealed interface StreamReceiveSide {
    /** A bidirectional stream, or one the peer opened: the peer's bytes, FIN or RESET_STREAM can arrive. */
    data object FromPeer : StreamReceiveSide

    /** A unidirectional stream this endpoint opened: quiche answers every read of it with `INVALID_STREAM_STATE`. */
    data object Never : StreamReceiveSide
}
