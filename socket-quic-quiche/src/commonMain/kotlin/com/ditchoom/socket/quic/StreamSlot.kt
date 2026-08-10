package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.Volatile

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
     * Set once quiche reports the stream's FIN — even when that FIN arrived *coalesced with the last
     * data chunk* (`stream_recv` → bytes > 0 **and** fin = true). That data chunk is returned to the
     * caller as [com.ditchoom.buffer.flow.ReadResult.Data], so the FIN itself can't be returned in the
     * same `read()`; this flag carries it to the next `read()`, which returns
     * [com.ditchoom.buffer.flow.ReadResult.End]. Without it, the reader would park on [dataSignal]
     * forever — quiche has already delivered the FIN, so no further data or readable-signal is coming.
     */
    @Volatile
    var finReceived = false

    /**
     * Stream bytes quiche had already delivered to us, drained out of the connection by
     * [QuicheDriver.drainReadableStreamsIntoSlots] at teardown because no reader had consumed them yet.
     *
     * The connection dying does not un-receive them (RFC 9000 §10.2: a CONNECTION_CLOSE ends the
     * connection; the stream data the transport already accepted and acknowledged is still the
     * application's). Without this queue those bytes died with `quiche_conn_free` and the pending
     * `read()` returned [com.ditchoom.buffer.flow.ReadResult.End] — indistinguishable from a clean FIN
     * (issue #318: `expected:<[ping]> but was:<[no_data:End]>`).
     *
     * UNLIMITED so the drain — which runs on the driver loop, where suspending is not an option — can
     * never block, and because quiche's own flow-control window already bounds how much there can be.
     * A [Channel] rather than a plain deque because producer (driver loop) and consumer (the reading
     * coroutine) are different threads. Ownership of a buffer taken from here transfers to the reader,
     * exactly like [QuicheStreamAdapter.streamRead]'s data path; anything still queued when the stream
     * is closed is released by [DriverStreamAdapter.releaseUndeliveredReads].
     */
    val pendingData = Channel<ReadBuffer>(Channel.UNLIMITED)
}
