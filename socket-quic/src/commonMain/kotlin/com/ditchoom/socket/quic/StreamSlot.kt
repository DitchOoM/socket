package com.ditchoom.socket.quic

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
     * Set once quiche reports the stream's FIN — even when that FIN arrived *coalesced with the last
     * data chunk* (`stream_recv` → bytes > 0 **and** fin = true). That data chunk is returned to the
     * caller as [com.ditchoom.buffer.flow.ReadResult.Data], so the FIN itself can't be returned in the
     * same `read()`; this flag carries it to the next `read()`, which returns
     * [com.ditchoom.buffer.flow.ReadResult.End]. Without it, the reader would park on [dataSignal]
     * forever — quiche has already delivered the FIN, so no further data or readable-signal is coming.
     */
    @Volatile
    var finReceived = false
}
