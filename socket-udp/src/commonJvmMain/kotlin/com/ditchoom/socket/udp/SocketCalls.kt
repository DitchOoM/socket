package com.ditchoom.socket.udp

import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The socket calls in progress on one NIO channel, so that closing it never waits for one.
 *
 * The JDK closes a selectable channel by taking the locks its reads and writes hold. A close that
 * lands while another thread is inside `receive` or `send` therefore parks the closing thread until
 * that call returns, and the closing thread is whoever called `close()`: an application's main
 * thread included.
 *
 * Here a close that finds a call in progress marks the channel closing and returns; the last call in
 * progress closes the channel as it returns, on its own thread, where the locks are free. A close
 * that finds no call in progress closes the channel itself, uncontended. Either way [closeChannel]
 * runs exactly once, and a call that starts after [close] throws [ClosedChannelException] without
 * touching the channel.
 */
internal class SocketCalls(
    private val closeChannel: () -> Unit,
) {
    // [CLOSING] once close() has been called, plus the number of calls in progress.
    private val state = AtomicInteger(0)

    /** Runs [call] counted as in progress. Throws [ClosedChannelException] once [close] has been called. */
    inline fun <T> during(call: () -> T): T {
        begin()
        try {
            return call()
        } finally {
            end()
        }
    }

    fun begin() {
        while (true) {
            val current = state.get()
            if (current and CLOSING != 0) throw ClosedChannelException()
            if (state.compareAndSet(current, current + 1)) return
        }
    }

    fun end() {
        if (state.decrementAndGet() == CLOSING) closeChannel()
    }

    /** Closes the channel now if no call is in progress, else when the last one returns. Idempotent. */
    fun close() {
        while (true) {
            val current = state.get()
            if (current and CLOSING != 0) return
            if (state.compareAndSet(current, current or CLOSING)) {
                if (current == 0) closeChannel()
                return
            }
        }
    }

    private companion object {
        const val CLOSING = 1 shl 30
    }
}
