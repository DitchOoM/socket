package com.ditchoom.socket.quic

import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Runnable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

// The JVM's deadline queue: the same structure `NativeDeadlineTimer` keeps on Kotlin/Native (a lock-free
// inbox drained into a heap that one thread owns), kept as a copy until both can share one in commonMain.

/** What [DeadlineQueue.nextDue] answers: when the queue next needs looking at, or that it holds nothing. */
internal sealed interface NextDue {
    /** Nothing is armed, disarmed deadlines awaiting removal included. */
    object None : NextDue

    /** The earliest deadline in the queue, disarmed or not, falls due at [nanos]. */
    class At(
        val nanos: Long,
    ) : NextDue
}

/**
 * A heap of deadlines owned by one thread, fed lock-free from any. Never blocks and takes the time as an
 * argument: [fireDue] runs, in order of due time and then of arming, every action due at the time it is
 * given.
 *
 * Arming allocates one [Node] and one [NodeState.Armed] (which carries the action); disarming allocates
 * nothing. The state is one reference updated by compare-and-set (a field updater, not an `AtomicReference`
 * per node), so a node costs two objects where an atomic state plus an atomic action plus the node cost
 * three, and a disarmed node holds no action. The timer thread's own bookkeeping (the heap's growth) is off
 * that path.
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
        @Volatile
        internal var state: NodeState = NodeState.Armed(action)
        internal var next: Inbox = Inbox.End

        /** Disarms the deadline; true when this call did, false when it had fired or was already disarmed. */
        fun disarm(): Boolean {
            val current = state
            if (current !is NodeState.Armed || !STATE.compareAndSet(this, current, NodeState.Disarmed)) return false
            queue.disarmed.incrementAndGet()
            return true
        }

        override fun dispose() {
            disarm()
        }

        /** The action, if this call is the one that takes the node from armed to fired; null when it was disarmed first. */
        internal fun claimToFire(): Runnable? {
            val current = state
            return if (current is NodeState.Armed && STATE.compareAndSet(this, current, NodeState.Fired)) current.action else null
        }

        internal fun isDisarmed(): Boolean = state === NodeState.Disarmed

        private companion object {
            val STATE: AtomicReferenceFieldUpdater<Node, NodeState> =
                AtomicReferenceFieldUpdater.newUpdater(Node::class.java, NodeState::class.java, "state")
        }
    }

    /** The inbox: a Treiber stack of nodes, newest first. */
    sealed interface Inbox {
        object End : Inbox
    }

    private val inbox = AtomicReference<Inbox>(Inbox.End)

    /** Nodes disarmed and not yet removed from the queue. */
    private val disarmed = AtomicInteger(0)

    /** Nodes ever armed; also the next node's [Node.seq]. */
    private val armed = AtomicLong(0L)

    /** Nodes taken out of the queue (fired, or dropped disarmed); written by the timer thread only. */
    @Volatile
    private var removed = 0L

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
        val node = Node(dueNanos, armed.getAndIncrement(), action, this)
        while (true) {
            val head = inbox.get()
            node.next = head
            if (inbox.compareAndSet(head, node)) return node
        }
    }

    /** Nodes held, disarmed ones included; what a sweep keeps bounded. */
    val size: Int get() = (armed.get() - removed).toInt()

    fun hasInbox(): Boolean = inbox.get() !== Inbox.End

    /**
     * Whether disarmed nodes are most of what the queue holds and numerous enough for [compact] to sweep.
     * Disarming is lock-free and leaves its node where it is, so under a busy stream whose bound never
     * expires they would pile up until the thread next looks; whoever arms asks this and wakes it to sweep,
     * which costs one pass per [compactMin] disarmed nodes.
     */
    fun needsSweep(): Boolean {
        val dead = disarmed.get()
        return dead >= compactMin && dead * 2 >= size
    }

    /** Moves what was armed into the heap, oldest first, so equal due times fire in the order they were armed. */
    private fun drain() {
        if (draining === Inbox.End) {
            var chain = inbox.getAndSet(Inbox.End)
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
            if (action != null) runGuarded(action) else disarmed.decrementAndGet()
        }
    }

    /**
     * The earliest deadline in the queue, after draining the inbox, a disarmed one included: the thread
     * sleeps towards it and drops it when it gets there, rather than going idle the moment everything
     * armed has been disarmed. An arming that is later than that, which is every arming of a bound that is
     * re-armed on each operation, then finds a thread that is already asleep until then and wakes nothing.
     */
    fun nextDue(): NextDue {
        drain()
        return if (heap.isEmpty()) NextDue.None else NextDue.At(heap[0].dueNanos)
    }

    /** Sweeps disarmed nodes out of the heap when they are most of it, so a burst of cancelled long deadlines is bounded. */
    private fun compact() {
        if (heap.size < compactMin || disarmed.get() * 2 < heap.size) return
        val before = heap.size
        heap.removeAll { it.isDisarmed() }
        val dropped = before - heap.size
        disarmed.addAndGet(-dropped)
        removed += dropped
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
        removed += 1
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
    }
}

/**
 * Runs a deadline's action. One that throws must not end the timer thread, which every other connection's
 * deadlines depend on, and must not vanish either: it goes to the thread's uncaught-exception handler.
 */
internal fun runGuarded(action: Runnable) {
    try {
        action.run()
    } catch (failure: Throwable) {
        reportOnThisThread(failure)
    }
}

/** Hands [failure] to this thread's uncaught-exception handler; a handler that throws is ignored, it cannot be reported to. */
internal fun reportOnThisThread(failure: Throwable) {
    val thread = Thread.currentThread()
    try {
        thread.uncaughtExceptionHandler.uncaughtException(thread, failure)
    } catch (_: Throwable) {
        // Nothing left to tell: the handler is the last resort, and the timer thread must survive it.
    }
}
