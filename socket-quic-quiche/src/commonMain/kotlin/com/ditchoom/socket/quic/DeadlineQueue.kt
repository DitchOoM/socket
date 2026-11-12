@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic

import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.concurrent.atomics.incrementAndFetch

/** What [DeadlineQueue.nextDue] answers: when the queue next needs looking at, or that there is nothing to wait for. */
internal sealed interface NextDue {
    /** Nothing worth waiting for: nothing armed, or only disarmed deadlines too near to sleep towards. */
    object None : NextDue

    /** The earliest deadline worth waking for, a disarmed one included when it is far off, falls due at [nanos]. */
    class At(
        val nanos: Long,
    ) : NextDue
}

/**
 * What a deadline is: [DeadlineNode.Armed] is the node itself (the node being its own armed state is what lets a
 * bound cost one object and not a node plus a state wrapper), or one of these two. A node leaves armed once.
 */
internal sealed interface NodeState {
    object Fired : NodeState

    object Disarmed : NodeState
}

/**
 * The cell a [DeadlineNode] keeps its [NodeState] in, compare-and-set. Platform code, so that the cell is the
 * node's own field and not a second object per deadline: the JVM extends `AtomicReference`, Kotlin/Native keeps
 * a volatile field it updates in place. Nothing public; [setInitial] is called once, from the node's
 * constructor, before the node is published.
 */
internal expect abstract class StateCell() {
    protected fun setInitial(state: NodeState)

    protected fun loadState(): NodeState

    protected fun swapState(
        expected: NodeState,
        new: NodeState,
    ): Boolean
}

/**
 * A deadline: one object that is its own queue entry (the inbox's link), its own armed state, its
 * disarm handle and, in a subclass, its own action. Allocating it is the whole cost of arming a bound.
 *
 * [fire] runs on the timer thread, once, if the deadline falls due armed. [release] runs once, on the thread
 * that disarmed it, so a subclass drops what it holds (a continuation, a scope) at disarm and not when the
 * deadline would have fallen due; it never runs when the deadline fired. The two are exclusive because each
 * is behind the compare-and-set that takes the node out of the armed state.
 */
internal abstract class DeadlineNode(
    private val queue: DeadlineQueue,
    val dueNanos: Long,
) : StateCell(),
    DisposableHandle,
    DeadlineQueue.Inbox,
    NodeState {
    /** Order of arming, for deadlines due at the same instant. */
    internal val seq: Long = queue.nextSeq()
    internal var next: DeadlineQueue.Inbox = DeadlineQueue.Inbox.End

    init {
        // The node is its own armed state.
        setInitial(this)
    }

    protected abstract fun fire()

    protected open fun release() = Unit

    /** Disarms the deadline; true when this call did, false when it had fired or was already disarmed. */
    fun disarm(): Boolean {
        if (loadState() !== this || !swapState(this, NodeState.Disarmed)) return false
        queue.noteDisarmed()
        release()
        return true
    }

    override fun dispose() {
        disarm()
    }

    /** Fires the action if this call is the one that takes the node from armed to fired; false when it was disarmed first. */
    internal fun fireIfArmed(): Boolean {
        if (loadState() !== this || !swapState(this, NodeState.Fired)) return false
        runGuarded(::fire)
        return true
    }

    internal fun isDisarmed(): Boolean = loadState() === NodeState.Disarmed
}

/** A [DeadlineNode] that runs a [Runnable]: what the code that is not on the stream-bound path arms. */
private class RunnableNode(
    queue: DeadlineQueue,
    dueNanos: Long,
    private var action: Runnable,
) : DeadlineNode(queue, dueNanos) {
    override fun fire() {
        action.run()
    }

    // The continuation an application's long timeout holds is dropped when the timeout is disarmed, not when it would have fired.
    override fun release() {
        action = NoAction
    }
}

private object NoAction : Runnable {
    override fun run() = Unit
}

/**
 * A heap of deadlines owned by one thread, fed lock-free from any. Never blocks and takes the time as an
 * argument: [fireDue] runs, in order of due time and then of arming, every action due at the time it is
 * given.
 *
 * Shared by the JVM's [DeadlineTimer] and Kotlin/Native's `NativeDeadlineTimer`; the thread, the clock and the
 * waiting are theirs, the queue is the same code. Only `kotlin.concurrent.atomics`, `Int` and `Long` are used
 * outside [StateCell], so it compiles for every target of the module (no pointer-width-dependent type, as
 * watchosArm64 needs).
 *
 * Arming allocates the [DeadlineNode] and nothing else; disarming allocates nothing. The timer thread's own
 * bookkeeping (the heap's growth) is off that path.
 *
 * [compactMin] is the size from which disarmed nodes are swept out of the heap; a model-checking test
 * lowers it so a scenario of a few nodes reaches the sweep.
 */
internal class DeadlineQueue(
    private val compactMin: Int = COMPACT_MIN,
) {
    /** The inbox: a Treiber stack of nodes, newest first. */
    sealed interface Inbox {
        object End : Inbox
    }

    private val inbox = AtomicReference<Inbox>(Inbox.End)

    /** Nodes disarmed and not yet removed from the queue. */
    private val disarmed = AtomicInt(0)

    /** Nodes made; also the next node's [DeadlineNode.seq]. */
    private val armed = AtomicLong(0L)

    /** Nodes taken out of the queue (fired, or dropped disarmed); written by the timer thread only. */
    private val removed = AtomicLong(0L)

    /** Nodes the timer thread has moved from the inbox into its heap so far. */
    private val drained = AtomicLong(0L)

    /** Deadlines the timer thread has taken off the inbox so far; a test uses it to see a pass has run. */
    val drainedTotal: Long get() = drained.load()

    // Owned by the thread that calls fireDue / nextDue; touched by nothing else.
    private val heap = ArrayList<DeadlineNode>(INITIAL_CAPACITY)

    /**
     * What a drain has taken off the inbox and not yet put in the heap, oldest first. A drain that fails
     * part way (the heap cannot grow) leaves its rest here, and the next drain finishes it, so no node
     * is lost to an error on the timer thread.
     */
    private var draining: Inbox = Inbox.End

    internal fun nextSeq(): Long = armed.fetchAndIncrement()

    internal fun noteDisarmed() {
        disarmed.incrementAndFetch()
    }

    /** Publishes [node], made on this queue; callable from any thread. */
    fun arm(node: DeadlineNode) {
        while (true) {
            val head = inbox.load()
            node.next = head
            if (inbox.compareAndSet(head, node)) return
        }
    }

    /** A deadline for [dueNanos] that runs [action]; not yet armed. */
    fun node(
        dueNanos: Long,
        action: Runnable,
    ): DeadlineNode = RunnableNode(this, dueNanos, action)

    /** Arms a new deadline for [dueNanos] that runs [action]. */
    fun arm(
        dueNanos: Long,
        action: Runnable,
    ): DeadlineNode = node(dueNanos, action).also(::arm)

    /** Nodes held, disarmed ones included; what a sweep keeps bounded. */
    val size: Int get() = (armed.load() - removed.load()).toInt()

    fun hasInbox(): Boolean = inbox.load() !== Inbox.End

    /**
     * Whether disarmed nodes are most of what the queue holds and numerous enough for [compact] to sweep.
     * Disarming is lock-free and leaves its node where it is, so under a busy stream whose bound never
     * expires they would pile up until the thread next looks; whoever arms asks this and wakes it to sweep,
     * which costs one pass per [compactMin] disarmed nodes.
     */
    fun needsSweep(): Boolean {
        val dead = disarmed.load()
        return dead >= compactMin && dead * 2 >= size
    }

    /** Moves what was armed into the heap, oldest first, so equal due times fire in the order they were armed. */
    private fun drain() {
        if (draining === Inbox.End) {
            var chain = inbox.exchange(Inbox.End)
            // The stack is newest-first: reverse it. Allocation-free, so nothing here can fail half way.
            var reversed: Inbox = Inbox.End
            while (chain is DeadlineNode) {
                val following = chain.next
                chain.next = reversed
                reversed = chain
                chain = following
            }
            draining = reversed
        }
        while (true) {
            val cursor =
                when (val rest = draining) {
                    Inbox.End -> break
                    is DeadlineNode -> rest
                }
            val following = cursor.next
            push(cursor)
            drained.incrementAndFetch()
            cursor.next = Inbox.End
            draining = following
        }
        compact()
    }

    /** Runs every live action due at [nowNanos], in order, and drops the disarmed nodes that are due. */
    fun fireDue(nowNanos: Long) {
        drain()
        while (heap.isNotEmpty()) {
            val top = heap[0]
            if (top.dueNanos > nowNanos) return
            pop()
            if (!top.fireIfArmed()) disarmed.decrementAndFetch()
        }
    }

    /**
     * When the queue next needs looking at, after draining the inbox: the earliest deadline, which may be a disarmed
     * one. The thread sleeps towards it and drops it there, so a bound re-armed on every operation (each later than
     * the disarmed one) finds a thread already asleep and wakes nothing.
     *
     * A disarmed deadline due within [staleHorizonNanos] of [nowNanos] is dropped instead: waking at it costs a pass
     * and saves a wake only for armings later still, and a deadline that short armed and disarmed over and over (the
     * driver's own timers, on Kotlin/Native) would wake the thread every time one fell due.
     */
    fun nextDue(
        nowNanos: Long,
        staleHorizonNanos: Long = STALE_HORIZON_NANOS,
    ): NextDue {
        drain()
        while (heap.isNotEmpty()) {
            val top = heap[0]
            if (top.isDisarmed() && top.dueNanos - nowNanos < staleHorizonNanos) {
                pop()
                disarmed.decrementAndFetch()
                continue
            }
            return NextDue.At(top.dueNanos)
        }
        return NextDue.None
    }

    /** Sweeps disarmed nodes out of the heap when they are most of it, so a burst of cancelled long deadlines is bounded. */
    private fun compact() {
        if (heap.size < compactMin || disarmed.load() * 2 < heap.size) return
        val before = heap.size
        heap.removeAll { it.isDisarmed() }
        val dropped = before - heap.size
        disarmed.addAndFetch(-dropped)
        removed.addAndFetch(dropped.toLong())
        for (i in heap.size / 2 - 1 downTo 0) siftDown(i)
    }

    private fun before(
        a: DeadlineNode,
        b: DeadlineNode,
    ): Boolean = a.dueNanos < b.dueNanos || (a.dueNanos == b.dueNanos && a.seq < b.seq)

    private fun push(node: DeadlineNode) {
        heap.add(node)
        siftUp(heap.size - 1)
    }

    private fun pop() {
        removed.incrementAndFetch()
        val last = heap.removeAt(heap.size - 1)
        if (heap.isNotEmpty()) {
            heap[0] = last
            siftDown(0)
        }
    }

    private fun siftUp(from: Int) {
        var i = from
        val node = heap[i]
        while (i > 0) {
            val parent = (i - 1) / 2
            if (!before(node, heap[parent])) break
            heap[i] = heap[parent]
            i = parent
        }
        heap[i] = node
    }

    private fun siftDown(from: Int) {
        var i = from
        val node = heap[i]
        while (true) {
            var child = 2 * i + 1
            if (child >= heap.size) break
            if (child + 1 < heap.size && before(heap[child + 1], heap[child])) child++
            if (!before(heap[child], node)) break
            heap[i] = heap[child]
            i = child
        }
        heap[i] = node
    }

    companion object {
        private const val INITIAL_CAPACITY = 64
        const val COMPACT_MIN = 4096

        /** A disarmed deadline nearer than this is dropped, not slept towards ([nextDue]): 100 ms. */
        const val STALE_HORIZON_NANOS = 100_000_000L
    }
}

/**
 * Runs a deadline's action. One that throws must not end the timer thread, which every other connection's
 * deadlines depend on, and must not vanish either: it is handed to [reportOnThisThread].
 */
internal inline fun runGuarded(action: () -> Unit) {
    try {
        action()
    } catch (failure: Throwable) {
        reportOnThisThread(failure)
    }
}

/**
 * Reports a failure on the timer thread without ending it: on the JVM to the thread's uncaught-exception
 * handler, on Kotlin/Native to the error stream. It never throws; a failure to report has nowhere left to go.
 */
internal expect fun reportOnThisThread(failure: Throwable)
