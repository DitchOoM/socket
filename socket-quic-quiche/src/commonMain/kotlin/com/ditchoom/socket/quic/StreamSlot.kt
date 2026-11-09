@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.channels.Channel
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
     * Stream bytes quiche had already delivered to us that no reader has taken yet. Two producers, both
     * cases where quiche moved the receive offset but the `read()` that asked for the bytes is gone:
     * [QuicheDriver.drainReadableStreamsIntoSlots] at connection teardown, and
     * [DriverStreamAdapter.salvageCancelledRecv] when a read's timeout or cancellation unwound it before
     * the driver answered its still-queued `StreamRecv`.
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
