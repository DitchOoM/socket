@file:OptIn(ObsoleteWorkersApi::class, ExperimentalNativeApi::class)

package com.ditchoom.socket.quic

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The thread every QUIC deadline on Kotlin/Native fires on, and the structure that holds them.
 *
 * kotlinx.coroutines' own timer for `Dispatchers.Default` is `DefaultExecutor`, a single worker: arming a
 * deadline (`withTimeout`, `select`'s `onTimeout`, `delay`) is `Worker.executeAfter`, which takes that
 * worker's queue lock, inserts into its delayed-job tree and may wake it, and nothing ever removes the
 * entry of a deadline that was disarmed, so a 30 s read bound that completes in 300 µs sits in the tree
 * for 30 s. A QUIC connection arms one on every driver turn and on every stream read and write; a trace
 * of an optimized macosArm64 binary (see scripts/native-perf) put that at 15% of the busy samples, most of
 * it threads queueing on that one lock.
 *
 * Here arming is a lock-free push of one node onto an inbox ([Inbox]); the timer thread drains the inbox
 * into a heap it alone owns, so the heap needs no synchronisation. Disarming is one compare-and-set on the
 * node. The thread is woken only when a new deadline is earlier than the one it is sleeping until, which
 * for the common case (a deadline re-armed a little later than the stale one it replaces) it is not.
 * A disarmed node is dropped when it reaches the top of the heap or, when they outnumber the live ones,
 * by a sweep ([compact]); its action is replaced on disarm so it no longer keeps a continuation alive.
 *
 * The queue itself, [DeadlineQueue], takes the time as an argument and never blocks, so its ordering is
 * tested without a clock. This class adds the thread and the clock.
 */
internal class NativeDeadlineTimer private constructor(
    private val queue: DeadlineQueue,
) {
    constructor() : this(DeadlineQueue())

    private val clock = TimeSource.Monotonic.markNow()

    /** What the timer thread is doing, for the arming side to decide whether it has to wake it. */
    private sealed interface TimerThread {
        /** Running a pass over the queue: it will see anything armed before its next sleep. */
        object Awake : TimerThread

        /** Parked until [dueNanos] (on [clock]) unless woken. */
        class Sleeping(
            val dueNanos: Long,
        ) : TimerThread

        /** [close]d: arming no longer schedules anything. */
        object Closed : TimerThread
    }

    private val thread = AtomicReference<TimerThread>(TimerThread.Awake)

    // Started with the timer: the first deadline of the process is armed by a connect, which is not a hot path.
    private val worker =
        Worker.start(name = "quic-deadline-timer").also { w -> w.executeAfter(0L) { run(w) } }

    /** Nanoseconds on [clock] now. */
    private fun now(): Long = clock.elapsedNow().inWholeNanoseconds

    /**
     * Runs [action] on the timer thread after [delay], unless the returned handle is disposed first.
     * A delay that is not finite is a wait for cancellation: nothing is scheduled, as in kotlinx's timers.
     */
    fun schedule(
        delay: Duration,
        action: Runnable,
    ): DisposableHandle {
        val wait = delay.inWholeNanoseconds
        if (!delay.isFinite() || wait >= Long.MAX_VALUE / 4) return Nothing
        val due = now() + maxOf(wait, 0L)
        val node = queue.arm(due, action)
        wakeIfEarlier(due)
        return node
    }

    private fun wakeIfEarlier(due: Long) {
        while (true) {
            when (val state = thread.value) {
                TimerThread.Awake, TimerThread.Closed -> return
                is TimerThread.Sleeping ->
                    if (due >= state.dueNanos) {
                        return
                    } else if (thread.compareAndSet(state, TimerThread.Awake)) {
                        worker.executeAfter(0L) {}
                        return
                    }
            }
        }
    }

    private fun run(self: Worker) {
        while (true) {
            while (true) {
                val before = thread.value
                if (before === TimerThread.Closed) return
                if (thread.compareAndSet(before, TimerThread.Awake)) break
            }
            queue.fireDue(now())
            val next = queue.nextDue()
            if (next != DeadlineQueue.NONE) {
                if (next <= now()) continue
            }
            val sleeping = TimerThread.Sleeping(if (next == DeadlineQueue.NONE) Long.MAX_VALUE else next)
            if (!thread.compareAndSet(TimerThread.Awake, sleeping)) {
                // Closed, or woken between the pass and here: another pass.
                if (thread.value === TimerThread.Closed) return
                continue
            }
            // An arm that raced the compare-and-set above saw Awake and did not wake us; it pushed first.
            if (queue.hasInbox()) {
                thread.compareAndSet(sleeping, TimerThread.Awake)
                continue
            }
            val wait = if (next == DeadlineQueue.NONE) PARK_FOREVER_MICROS else maxOf((next - now()) / 1_000L, 1L)
            self.park(minOf(wait, PARK_FOREVER_MICROS), true)
        }
    }

    /** Stops the thread; deadlines armed afterwards are not scheduled. Tests only: production's is never stopped. */
    fun close() {
        thread.value = TimerThread.Closed
        worker.executeAfter(0L) {}
        worker.requestTermination(processScheduledJobs = false)
    }

    /** [dispatcher], with every `delay`, `withTimeout` and `onTimeout` inside it firing on this timer. */
    fun over(dispatcher: CoroutineDispatcher): CoroutineDispatcher = TimedDispatcher(dispatcher, this)

    companion object {
        /** The timer every production connection and server on this process shares. */
        val Shared: NativeDeadlineTimer by lazy { NativeDeadlineTimer() }

        private const val PARK_FOREVER_MICROS = 3_600_000_000L

        private object Nothing : DisposableHandle {
            override fun dispose() = Unit
        }
    }
}

/**
 * A heap of deadlines owned by one thread, fed lock-free from any. Never blocks and takes the time as an
 * argument: [fireDue] runs, in order of due time and then of arming, every action due at the time it is
 * given.
 */
internal class DeadlineQueue {
    /** A disarmable deadline: what [arm] returns. */
    class Node internal constructor(
        val dueNanos: Long,
        action: Runnable,
        private val queue: DeadlineQueue,
    ) : DisposableHandle,
        Inbox {
        internal val state = AtomicReference<NodeState>(NodeState.Armed)
        internal val action = AtomicReference(action)
        internal var next: Inbox = Inbox.End
        internal var seq: Long = 0L

        override fun dispose() {
            if (state.compareAndSet(NodeState.Armed, NodeState.Disarmed)) {
                // Drop the reference the action holds (a coroutine's continuation) now rather than at its due time.
                action.value = DoNothing
                queue.disarmed.incrementAndGet()
            }
        }
    }

    internal sealed interface NodeState {
        object Armed : NodeState

        object Fired : NodeState

        object Disarmed : NodeState
    }

    /** The inbox: a Treiber stack of nodes, newest first. */
    sealed interface Inbox {
        object End : Inbox
    }

    private val inbox = AtomicReference<Inbox>(Inbox.End)
    internal val disarmed = AtomicInt(0)
    private val armed = AtomicLong(0L)

    // Owned by the thread that calls fireDue / nextDue; touched by nothing else.
    private var heap = arrayOfNulls<Node>(INITIAL_CAPACITY)
    private var size = 0

    /** Schedules [action] for [dueNanos]; callable from any thread. */
    fun arm(
        dueNanos: Long,
        action: Runnable,
    ): Node {
        val node = Node(dueNanos, action, this)
        while (true) {
            val head = inbox.value
            node.next = head
            if (inbox.compareAndSet(head, node)) return node
        }
    }

    fun hasInbox(): Boolean = inbox.value !== Inbox.End

    /** Moves what was armed into the heap, oldest first, so equal due times fire in the order they were armed. */
    private fun drain() {
        var chain = inbox.getAndSet(Inbox.End)
        if (chain === Inbox.End) return
        // The stack is newest-first: reverse it.
        var reversed: Inbox = Inbox.End
        while (chain is Node) {
            val following = chain.next
            chain.next = reversed
            reversed = chain
            chain = following
        }
        var cursor = reversed
        while (cursor is Node) {
            val following = cursor.next
            cursor.next = Inbox.End
            cursor.seq = armed.incrementAndGet()
            push(cursor)
            cursor = following
        }
        compact()
    }

    /** Runs every live action due at [nowNanos], in order. */
    fun fireDue(nowNanos: Long) {
        drain()
        while (size > 0) {
            val top = heap[0]!!
            if (top.state.value === NodeState.Disarmed) {
                pop()
                disarmed.decrementAndGet()
                continue
            }
            if (top.dueNanos > nowNanos) return
            pop()
            if (top.state.compareAndSet(NodeState.Armed, NodeState.Fired)) {
                top.action.value.run()
            } else {
                disarmed.decrementAndGet()
            }
        }
    }

    /** The due time of the earliest live deadline after draining the inbox, or [NONE]. */
    fun nextDue(): Long {
        drain()
        while (size > 0) {
            val top = heap[0]!!
            if (top.state.value === NodeState.Disarmed) {
                pop()
                disarmed.decrementAndGet()
                continue
            }
            return top.dueNanos
        }
        return NONE
    }

    /** Sweeps disarmed nodes out of the heap when they are most of it, so a burst of cancelled long deadlines is bounded. */
    private fun compact() {
        if (size < COMPACT_MIN || disarmed.value * 2 < size) return
        var kept = 0
        var removed = 0
        for (i in 0 until size) {
            val node = heap[i]!!
            if (node.state.value === NodeState.Disarmed) removed++ else heap[kept++] = node
        }
        for (i in kept until size) heap[i] = null
        size = kept
        disarmed.addAndGet(-removed)
        for (i in size / 2 - 1 downTo 0) siftDown(i)
    }

    private fun before(
        a: Node,
        b: Node,
    ): Boolean = a.dueNanos < b.dueNanos || (a.dueNanos == b.dueNanos && a.seq < b.seq)

    private fun push(node: Node) {
        if (size == heap.size) heap = heap.copyOf(size * 2)
        heap[size] = node
        siftUp(size++)
    }

    private fun pop() {
        heap[0] = heap[--size]
        heap[size] = null
        if (size > 0) siftDown(0)
    }

    private fun siftUp(from: Int) {
        var i = from
        val node = heap[i]!!
        while (i > 0) {
            val parent = (i - 1) / 2
            if (!before(node, heap[parent]!!)) break
            heap[i] = heap[parent]
            i = parent
        }
        heap[i] = node
    }

    private fun siftDown(from: Int) {
        var i = from
        val node = heap[i]!!
        while (true) {
            var child = 2 * i + 1
            if (child >= size) break
            if (child + 1 < size && before(heap[child + 1]!!, heap[child]!!)) child++
            if (!before(heap[child]!!, node)) break
            heap[i] = heap[child]
            i = child
        }
        heap[i] = node
    }

    internal fun liveAndDisarmed(): Pair<Int, Int> = size to disarmed.value

    companion object {
        /** [nextDue]'s answer when nothing live is armed. */
        const val NONE = Long.MIN_VALUE

        private const val INITIAL_CAPACITY = 64
        private const val COMPACT_MIN = 1024

        private object DoNothing : Runnable {
            override fun run() = Unit
        }
    }
}

/** Dispatches through [delegate]; every timer armed in it fires on [timer]. */
@OptIn(InternalCoroutinesApi::class)
private class TimedDispatcher(
    private val delegate: CoroutineDispatcher,
    val timer: NativeDeadlineTimer,
) : CoroutineDispatcher(),
    Delay {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = delegate.isDispatchNeeded(context)

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) = delegate.dispatch(context, block)

    override fun dispatchYield(
        context: CoroutineContext,
        block: Runnable,
    ) = delegate.dispatchYield(context, block)

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val due = timer.schedule(timeMillis.millisOrNever(), { continuation.resume(Unit) })
        continuation.invokeOnCancellation { due.dispose() }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle = timer.schedule(timeMillis.millisOrNever(), block)

    override fun toString(): String = "$delegate+NativeDeadlineTimer"
}

/** kotlinx hands `Duration.INFINITE` over as `Long.MAX_VALUE` milliseconds. */
private fun Long.millisOrNever(): Duration = if (this >= Long.MAX_VALUE / 2_000_000L) Duration.INFINITE else this.milliseconds

/**
 * `withTimeout`, but its deadline is armed on [NativeDeadlineTimer.Shared] instead of whatever timer the
 * caller's dispatcher has (the caller's is its own: a stream read is called from the application's
 * coroutine, not the driver's). Same contract: a [TimeoutCancellationException] when it expires, and
 * it is the expiry that cancels [block], so a result produced at the same instant loses the race exactly as
 * with `withTimeout`.
 *
 * kotlinx.coroutines gives [TimeoutCancellationException] no public constructor, so the expiry cancels the
 * scope with [DeadlineExpired] and the exception the caller sees is the one `withTimeout` itself throws for
 * a bound of zero, made only when a deadline did expire. Its message is therefore "Timed out immediately",
 * not the elapsed bound.
 */
internal actual suspend fun <T> withStreamDeadline(
    timeout: Duration,
    block: suspend CoroutineScope.() -> T,
): T {
    if (timeout <= Duration.ZERO) throw timedOut()
    return try {
        coroutineScope {
            val scope = this
            val due = NativeDeadlineTimer.Shared.schedule(timeout) { scope.cancel(DeadlineExpired) }
            try {
                block()
            } finally {
                due.dispose()
            }
        }
    } catch (expired: DeadlineExpired) {
        throw timedOut()
    }
}

/** What an expired [withStreamDeadline] cancels its scope with; never escapes it. */
private object DeadlineExpired : CancellationException("deadline expired")

private suspend fun timedOut(): TimeoutCancellationException =
    try {
        withTimeout(Duration.ZERO) {}
        error("a bound of zero returned")
    } catch (e: TimeoutCancellationException) {
        e
    }
