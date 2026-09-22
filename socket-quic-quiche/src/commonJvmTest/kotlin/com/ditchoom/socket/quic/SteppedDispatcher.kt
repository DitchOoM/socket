package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * A dispatcher that runs nothing until the test says so, on the test's own thread.
 *
 * Every resumption of a coroutine on it — its start, a wake from a channel, a cancellation — is
 * queued, so a test can let one side of a hand-off complete and hold the other side's resumption
 * while something else happens, then run it. Deterministic by construction: the only thread that
 * ever executes these coroutines is the one calling [runUntilIdle].
 */
internal class SteppedDispatcher : CoroutineDispatcher() {
    private val pending = LinkedBlockingQueue<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        pending.put(block)
    }

    /** Resumptions queued and not yet run. */
    val pendingCount: Int get() = pending.size

    /** Run every queued resumption, and every one they queue in turn, until none is left. */
    fun runUntilIdle() {
        while (true) (pending.poll() ?: return).run()
    }

    /**
     * Wait up to [timeout] for a resumption another thread will queue, then [runUntilIdle].
     * False if none arrived.
     */
    fun awaitThenRunUntilIdle(timeout: Duration): Boolean {
        val first = pending.poll(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) ?: return false
        first.run()
        runUntilIdle()
        return true
    }
}
