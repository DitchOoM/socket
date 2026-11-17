package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos

internal enum class Budget { Left, Spent }

/**
 * How long one pass may receive before it serves. A socket that never empties, or a server starting handshakes, would
 * otherwise keep every connection on the loop waiting for its output; what is left is read in the next pass. The clock
 * is read once per batch taken or chunk of handshakes started, never per connection.
 */
@LoopOnly
internal class ReceiveBudget(
    private val clock: LoopClock,
    private val nanos: Long = RECEIVE_NANOS,
) {
    private var until = 0L

    fun begin(now: Nanos) {
        until = now.value + nanos
    }

    /** Whether this pass's receive time is used up, at the clock now. */
    fun state(): Budget = if (clock.now().value >= until) Budget.Spent else Budget.Left

    private companion object {
        /** Two milliseconds: a full per-pass count of datagrams on a fast device, a few batches on a starved one. */
        const val RECEIVE_NANOS = 2_000_000L
    }
}
