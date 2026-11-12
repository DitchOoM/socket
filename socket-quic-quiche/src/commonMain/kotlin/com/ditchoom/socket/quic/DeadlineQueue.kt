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
 * A heap of deadlines owned by one thread, fed lock-free from any. Never blocks and takes the time as an
 * argument: [fireDue] runs, in order of due time and then of arming, every action due at the time it is
 * given.
 *
 * Shared by the JVM's [DeadlineTimer] and Kotlin/Native's `NativeDeadlineTimer`; the thread, the clock and the
 * waiting are theirs, the queue is the same code. Only `kotlin.concurrent.atomics` and `Int`/`Long` are used, so
 * it compiles for every target of the module (no pointer-width-dependent type, as watchosArm64 needs).
 *
 * Arming allocates a [Node], its state cell and a [NodeState.Armed] (which carries the action); disarming
 * allocates nothing, and a disarmed node holds no action. The timer thread's own bookkeeping (the heap's
 * growth) is off that path.
 *
 * [compactMin] is the size from which disarmed nodes are swept out of the heap; a model-checking test
 * lowers it so a scenario of a few nodes reaches the sweep.
 */
internal class DeadlineQueue(
    private val compactMin: Int = COMPACT_MIN,
) {
    /** What a node is: armed with its action, fired, or disarmed. A node leaves [Armed] once, never returns. */
    internal sealed interface NodeState {
        class Armed(
            val action: Runnable,
        ) : NodeState

        object Fired : NodeState

        object Disarmed : NodeState
    }

    /** A disarmable deadline: what [arm] returns. */
    class Node internal constructor(
        val dueNanos: Long,
        /** Order of arming, for deadlines due at the same instant. */
        val seq: Long,
        action: Runnable,
        private val queue: DeadlineQueue,
    ) : DisposableHandle,
        Inbox {
        internal val state = AtomicReference<NodeState>(NodeState.Armed(action))
        internal var next: Inbox = Inbox.End

        /** Disarms the deadline; true when this call did, false when it had fired or was already disarmed. */
        fun disarm(): Boolean {
            val current = state.load()
            if (current !is NodeState.Armed || !state.compareAndSet(current, NodeState.Disarmed)) return false
            queue.disarmed.incrementAndFetch()
            return true
        }

        override fun dispose() {
            disarm()
        }

        /** The action, if this call is the one that takes the node from armed to fired; null when it was disarmed first. */
        internal fun claimToFire(): Runnable? {
            val current = state.load()
            return if (current is NodeState.Armed && state.compareAndSet(current, NodeState.Fired)) current.action else null
        }

        internal fun isDisarmed(): Boolean = state.load() === NodeState.Disarmed
    }

    /** The inbox: a Treiber stack of nodes, newest first. */
    sealed interface Inbox {
        object End : Inbox
    }

    private val inbox = AtomicReference<Inbox>(Inbox.End)

    /** Nodes disarmed and not yet removed from the queue. */
    private val disarmed = AtomicInt(0)

    /** Nodes ever armed; also the next node's [Node.seq]. */
    private val armed = AtomicLong(0L)

    /** Nodes taken out of the queue (fired, or dropped disarmed); written by the timer thread only. */
    private val removed = AtomicLong(0L)

    /** Nodes the timer thread has moved from the inbox into its heap so far. */
    private val drained = AtomicLong(0L)

    /** Deadlines the timer thread has taken off the inbox so far; a test uses it to see a pass has run. */
    val drainedTotal: Long get() = drained.load()

    // Owned by the thread that calls fireDue / nextDue; touched by nothing else.
    private val heap = ArrayList<Node>(INITIAL_CAPACITY)

    /**
     * What a drain has taken off the inbox and not yet put in the heap, oldest first. A drain that fails
     * part way (the heap cannot grow) leaves its rest here, and the next drain finishes it, so no node
     * is lost to an error on the timer thread.
     */
    private var draining: Inbox = Inbox.End

    /** Schedules [action] for [dueNanos]; callable from any thread. */
    fun arm(
        dueNanos: Long,
        action: Runnable,
    ): Node {
        val node = Node(dueNanos, armed.fetchAndIncrement(), action, this)
        while (true) {
            val head = inbox.load()
            node.next = head
            if (inbox.compareAndSet(head, node)) return node
        }
    }

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
            while (chain is Node) {
                val following = chain.next
                chain.next = reversed
                reversed = chain
                chain = following
            }
            draining = reversed
        }
        while (true) {
            val cursor = draining as? Node ?: break
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
            val action = top.claimToFire()
            if (action != null) runGuarded(action) else disarmed.decrementAndFetch()
        }
    }

    /**
     * When the queue next needs looking at, after draining the inbox: the earliest deadline, which may be a
     * disarmed one. The thread sleeps towards it and drops it when it gets there, rather than going idle the
     * moment everything armed has been disarmed; an arming that is later than that, which is every arming of a
     * bound that is re-armed on each operation, then finds a thread already asleep until then and wakes nothing.
     *
     * Only for a disarmed deadline that is far away. Waking at one costs a pass, and it saves a wake only for
     * armings that are later still, so a disarmed deadline due within [staleHorizonNanos] of [nowNanos] is
     * dropped here and not slept towards: a deadline that short that is armed and disarmed over and over (the
     * driver's own timers, on Kotlin/Native, which are armed on this queue) would otherwise wake the thread
     * every time one fell due, measured at some twenty times the passes of skipping them.
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
        a: Node,
        b: Node,
    ): Boolean = a.dueNanos < b.dueNanos || (a.dueNanos == b.dueNanos && a.seq < b.seq)

    private fun push(node: Node) {
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
internal fun runGuarded(action: Runnable) {
    try {
        action.run()
    } catch (failure: Throwable) {
        reportOnThisThread(failure)
    }
}

/**
 * Reports a failure on the timer thread without ending it: on the JVM to the thread's uncaught-exception
 * handler, on Kotlin/Native to the error stream. It never throws; a failure to report has nowhere left to go.
 */
internal expect fun reportOnThisThread(failure: Throwable)
