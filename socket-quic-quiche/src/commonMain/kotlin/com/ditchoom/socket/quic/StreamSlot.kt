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
class StreamSlot(
    val id: QuicStreamId,
) {
    /** Whether this stream can ever carry bytes toward us; set once, while the slot is made. See [StreamReceiveSide]. */
    private var receiveSide: StreamReceiveSide = StreamReceiveSide.FromPeer

    internal constructor(id: QuicStreamId, receiveSide: StreamReceiveSide) : this(id) {
        this.receiveSide = receiveSide
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
     * Park the reader until the driver signals [dataSignal], advertising a read of [size] bytes for the
     * driver to fill ([takeWaitingRead]) meanwhile. The advert is withdrawn BEFORE this returns, so the
     * caller's next look at [pendingData] cannot be followed by a drain it did not see: once the advert
     * is gone the driver only signals. A drain that took the advert first may still be landing as the
     * reader looks; the driver answers that reader's next `StreamRecv` with `Done` while the queue holds
     * anything or the end is latched (see `QuicheDriver.execute`), so the queued chunk is read before any
     * byte quiche has after it, and the end of a stream quiche has already collected is the reader's
     * verdict rather than a question put to quiche.
     */
    internal suspend fun awaitData(size: Int) {
        advertiseWaitingRead(size)
        try {
            dataSignal.receive()
        } catch (cancelled: CancellationException) {
            // A receive that was cancelled can swallow the signal it was woken by (a channel gives no
            // guarantee otherwise), and the bytes that signal announced stay in quiche. The next read
            // asks quiche rather than trusting the absence of a signal. A closed channel is not this:
            // nothing more will be signalled, and the read reports the connection's end.
            noteWaitCancelled()
            throw cancelled
        } finally {
            withdrawWaitingRead()
        }
    }

    /**
     * Set when quiche may hold bytes of this stream that no signal in [dataSignal] still announces: a
     * read parked in [awaitData] was cancelled, or the loop handed a parked read a chunk that filled the
     * size it asked for ([QuicheDriver] `handToWaitingRead`), so more may follow it. Read-and-cleared by
     * [readNeedsQuicheAnswer].
     */
    private val quicheMayHoldBytes = AtomicInt(0)

    /** A parked wait was cancelled, perhaps with the signal that woke it in flight: the next read asks. */
    internal fun noteWaitCancelled() = quicheMayHoldBytes.store(1)

    /**
     * Loop only: hands the read parked in [awaitData] the [chunk] the loop took out of quiche for it
     * ([deliverDrained]), and marks the stream when the chunk filled the [requested] size: quiche may hold
     * more, and the signal that wakes the read is consumed by the wake. Returns false when the queue is
     * closed and [chunk] was not taken.
     */
    internal fun deliverHandedOff(
        chunk: ReadBuffer,
        fin: Boolean,
        requested: Int,
        bytesRead: Int,
    ): Boolean {
        if (bytesRead >= requested) quicheMayHoldBytes.store(1)
        return deliverDrained(chunk, fin)
    }

    /**
     * Whether a read that found nothing queued must put a `StreamRecv` to the loop before it parks.
     *
     * The invariant that lets a read park without asking: quiche's readable set is level-triggered (a
     * stream stays in it until the application drains it), it grows only when a datagram is received, and
     * the loop sweeps it at the end of every turn (after each batch of commands, up to
     * `MAX_COMMANDS_PER_TURN`, and so after every datagram). So every stream with bytes, a FIN or a reset
     * waiting in quiche is, after the turn that received them, either handed to a read parked in
     * [awaitData], or left as a signal in [dataSignal]; and the sweep that left a signal repeats for as
     * long as the bytes stay unread. A read that takes no signal here, and has no [quicheMayHoldBytes]
     * mark, can park at once: whatever arrives later reaches it through the same sweep. Asking anyway
     * cost a loop turn, a flush and two thread hand-offs per read that had to wait for the network.
     *
     * The mark is the one thing outside that signal: [dataSignal] is conflated and a wake consumes it, so
     * bytes that remain after a hand-off chunk that filled its request, or after a wait that was cancelled
     * with a signal in flight, have no signal left. Combinations of signal and mark: neither (park); signal
     * only (ask, the common wake-up); mark only (ask); both (ask once, a stale second signal costs one extra
     * ask later and no more). Every one of them errs toward asking.
     *
     * Both reads are always made (no short circuit): a signal left behind would make the next read ask
     * for nothing.
     */
    internal fun readNeedsQuicheAnswer(): Boolean {
        val signalled = dataSignal.tryReceive().isSuccess
        val marked = quicheMayHoldBytes.exchange(0) == 1
        return signalled || marked || answersWithAnError()
    }

    /**
     * Whether quiche's answer to a read here can be a typed error instead of `Done`, which no signal ever
     * announces: `stream_recv` fails with `INVALID_STREAM_STATE` on a locally initiated unidirectional
     * stream (it can never carry bytes toward us), and on a stream quiche has already collected, which is
     * where a stream whose read side the application shut ends up. Such a read must ask, or it would park
     * for a signal that cannot come instead of failing with the error it always got.
     */
    private fun answersWithAnError(): Boolean =
        when (receiveSide) {
            StreamReceiveSide.Never -> true
            StreamReceiveSide.FromPeer -> readState.load() == StreamReadState.Finished
        }

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

/**
 * Whether bytes can ever arrive on a stream's read side. Decided when the slot is made, from the stream's
 * direction and who opened it, so a read on a stream that can never carry bytes cannot be mistaken for a
 * read that merely has not been signalled yet.
 */
internal sealed interface StreamReceiveSide {
    /** A bidirectional stream, or one the peer opened: the peer's bytes, FIN or RESET_STREAM can arrive. */
    data object FromPeer : StreamReceiveSide

    /** A unidirectional stream this endpoint opened: quiche refuses every read of it with `INVALID_STREAM_STATE`. */
    data object Never : StreamReceiveSide
}
