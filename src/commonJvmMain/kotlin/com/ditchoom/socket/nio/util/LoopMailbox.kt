package com.ditchoom.socket.nio.util

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How other threads hand work to a loop thread that sleeps in a blocking call, with at most one wake-up per
 * sleep however many items arrive. Factored out of [SelectorLoop] so the protocol can be model checked
 * without a [java.nio.channels.Selector].
 *
 * The protocol, which the loop thread follows each turn: [poll] until empty, then [prepareToSleep], and
 * only if that says so, block in a call that returns at once if [wake] ran since the last turn began (a
 * selector's `wakeup()` does). A submitter [post]s, and calls [wake] only when it is the first since the
 * loop last prepared to sleep. No lost wake-up: an item posted after the loop's last look at the queue either
 * finds the flag already set by an earlier poster whose [wake] is in flight (the loop's blocking call sees
 * it) or sets the flag itself and wakes; an item posted before [prepareToSleep] is seen by its emptiness check.
 */
internal class LoopMailbox<T : Any>(
    private val wake: () -> Unit,
) {
    private val queue = ConcurrentLinkedQueue<T>()
    private val wakeupSent = AtomicBoolean(false)

    /** [onLoopThread]: the loop is running, so it will look at the queue again before it sleeps. */
    fun post(
        item: T,
        onLoopThread: Boolean,
    ) {
        queue.add(item)
        if (!onLoopThread && wakeupSent.compareAndSet(false, true)) wake()
    }

    fun poll(): T? = queue.poll()

    fun isEmpty(): Boolean = queue.isEmpty()

    /** Loop thread, before sleeping: later posters must wake it again. True when nothing is queued, so it may sleep. */
    fun prepareToSleep(): Boolean {
        wakeupSent.set(false)
        return queue.isEmpty()
    }
}
