package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos

internal enum class Budget { Left, Spent }

/**
 * How long one pass may start handshakes before it serves. A server starting a pass's whole bound of them on a slow
 * device kept every connection on the loop waiting for its output for over a second; what is held is started in the
 * next pass. Reading the socket is not budgeted: a datagram left in the kernel is read stale. The clock is read once
 * per chunk of handshakes started, never per connection.
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
        /** Two milliseconds: a pass's whole bound of handshakes on a fast device, a chunk on a starved one. */
        const val RECEIVE_NANOS = 2_000_000L
    }
}
