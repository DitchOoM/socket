@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.DeadlineFired
import com.ditchoom.socket.quic.DeadlineNode
import com.ditchoom.socket.quic.DeadlineQueue
import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.Rearmable
import com.ditchoom.socket.quic.Rearmables
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.CallerCounts
import com.ditchoom.socket.quic.core.observe.Queue
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.time.Nanos
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What the loop does after [LoopPort.beginSleep]. */
internal enum class SleepPlan {
    /** Block in the poller. */
    Await,

    /** Work or a deadline arrived first: run another pass. */
    Skip,
}

/**
 * The any-thread face of a loop: hand it work, arm deadlines on it, and it wakes the loop only when the loop has
 * to know. The wake rule: a thread wakes a sleeping loop when it submits work, or arms a deadline earlier than the
 * time the loop sleeps until. A deadline later than the loop's next wake costs it nothing, and a loop that is awake
 * or already woken is not woken again.
 *
 * The loop thread calls [fireDue], [runWork], [nextDue], [beginSleep], [endSleep] and [stop]; every other function
 * is for any thread.
 */
@Queue
internal class LoopPort(
    private val poller: Poller,
    private val callers: CallerCounts = CallerCounts(),
) {
    private val deadlines = DeadlineQueue()
    private val rearmables = Rearmables()
    private val inbox = WorkInbox<LoopWork>()
    private val sleep = AtomicInt(AWAKE)
    private val poked = AtomicInt(NOT_POKED)
    private val sleepsUntil = AtomicLong(0L)
    private val runner: (LoopWork) -> Unit = { it.run(this) }

    /** Any thread. */
    fun submit(work: LoopWork) {
        when (val pushed = inbox.push(work)) {
            is Shut -> work.gone(pushed.end)
            is Pushed.Open -> wakeIfAsleep()
        }
    }

    /**
     * Any thread. The loop has news that is in no queue of this port (a socket's completion arrived): it runs another
     * pass before it sleeps, and is woken if it sleeps already. Allocates nothing; many pokes before a pass are one.
     */
    fun poke() {
        poked.store(POKED)
        wakeIfAsleep()
    }

    /** Any thread. Arms [action] for [due]; the returned node disarms it. */
    fun arm(
        due: Nanos,
        action: Runnable,
    ): DeadlineNode {
        val node = deadlines.node(due.value, action)
        arm(node)
        return node
    }

    /** Any thread. Arms [node], made by [node]. */
    fun arm(node: DeadlineNode) {
        deadlines.arm(node)
        wakeFor(node.dueNanos)
    }

    /** Wakes a loop that sleeps past [dueNanos]. */
    private fun wakeFor(dueNanos: Long) {
        when (sleep.load()) {
            ASLEEP_FOR_EVER -> wakeIfAsleep()
            ASLEEP_UNTIL -> if (dueNanos < sleepsUntil.load()) wakeIfAsleep()
        }
    }

    /** Any thread. A deadline that runs [action] on the loop, made once and re-armed in place; not armed. */
    fun rearmable(action: DeadlineFired): Rearmable = rearmables.make(action)

    /** Any thread. Arms [deadline] for [due], in place of what it was armed for; allocates nothing. */
    fun arm(
        deadline: Rearmable,
        due: Nanos,
    ) {
        deadline.arm(due.value)
        wakeFor(due.value)
    }

    /** Any thread. A node for [arm], not yet armed. */
    fun node(
        due: Nanos,
        action: Runnable,
    ): DeadlineNode = deadlines.node(due.value, action)

    /** Loop thread. */
    fun fireDue(now: Nanos) {
        deadlines.fireDue(now.value)
        rearmables.fireDue(now.value)
    }

    /** Any thread. One-off deadlines made so far: each is one arming ([arm], [node]). */
    fun deadlinesMade(): Long = deadlines.madeTotal

    /** Loop thread, or a test between operations. Re-armings of [Rearmable] deadlines the loop has taken so far. */
    fun rearmsTaken(): Long = rearmables.postsTaken

    /** Loop thread. Runs every item submitted before this call, oldest first; returns how many. */
    fun runWork(): Int = inbox.drain(runner)

    /** Loop thread. When the loop next needs to run without input. */
    fun nextDue(now: Nanos): NextDue {
        val nodes = deadlines.nextDue(now.value)
        val rearmed = rearmables.nextDue()
        return nodes.fold(none = { rearmed }, due = { node -> rearmed.fold(none = { nodes }, due = { if (it < node) rearmed else nodes }) })
    }

    /**
     * Loop thread. Publishes the time the loop sleeps until, then checks once more for work, deadlines and pokes that
     * arrived before the sleep was visible; one that arrives after sees the sleep and wakes the loop.
     */
    fun beginSleep(next: NextDue): SleepPlan {
        next.fold(
            none = { sleep.store(ASLEEP_FOR_EVER) },
            due = {
                sleepsUntil.store(it)
                sleep.store(ASLEEP_UNTIL)
            },
        )
        val news = poked.exchange(NOT_POKED) == POKED
        return if (inbox.looksEmpty() && !deadlines.hasInbox() && !rearmables.hasInbox() && !news) SleepPlan.Await else SleepPlan.Skip
    }

    /** Loop thread, after the poller returned or the sleep was skipped. */
    fun endSleep() = sleep.store(AWAKE)

    /** Loop thread, once. Work still queued, and work submitted later, is told [end]. */
    fun stop(end: ConnEnd): Int = inbox.shut(end) { it.gone(end) }

    private fun wakeIfAsleep() {
        val seen = sleep.load()
        if ((seen == ASLEEP_FOR_EVER || seen == ASLEEP_UNTIL) && sleep.compareAndSet(seen, WOKEN)) {
            callers.woke()
            poller.wake()
        }
    }
}

private const val AWAKE = 0
private const val ASLEEP_FOR_EVER = 1
private const val ASLEEP_UNTIL = 2
private const val WOKEN = 3
private const val NOT_POKED = 0
private const val POKED = 1
