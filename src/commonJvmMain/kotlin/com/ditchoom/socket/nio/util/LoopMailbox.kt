package com.ditchoom.socket.nio.util

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How other threads hand work to a loop thread that sleeps in a blocking call, with at most one wake-up per
 * sleep however many items arrive.
 *
 * The loop thread each turn: [poll] until empty, then [prepareToSleep], and only if that says so, block in a
 * call that returns at once if [wake] ran since the turn began (a selector's `wakeup()` does). A submitter
 * [post]s, and calls [wake] only when it is the first since the loop last prepared to sleep. An item posted
 * after the loop's last look at the queue either finds the flag set by an earlier poster whose [wake] is in
 * flight, or sets it and wakes; an item posted before [prepareToSleep] is seen by its emptiness check.
 */
internal class LoopMailbox<T : Any>(
    private val wake: () -> Unit,
) {
    private val queue = ConcurrentLinkedQueue<T>()
    private val wakeupSent = AtomicBoolean(false)

    /** From any thread but the loop's. */
    fun post(item: T) {
        queue.add(item)
        if (wakeupSent.compareAndSet(false, true)) wake()
    }

    /** From the loop thread, which looks at the queue again before it sleeps. */
    fun postFromLoopThread(item: T) {
        queue.add(item)
    }

    fun poll(): T? = queue.poll()

    fun isEmpty(): Boolean = queue.isEmpty()

    /** Loop thread, before sleeping: later posters must wake it again. True when nothing is queued, so it may sleep. */
    fun prepareToSleep(): Boolean {
        wakeupSent.set(false)
        return queue.isEmpty()
    }
}
