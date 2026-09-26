@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.Volatile
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
     */
    @Volatile
    var end: StreamEnd = StreamEnd.Open

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
