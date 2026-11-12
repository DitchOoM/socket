@file:OptIn(ObsoleteWorkersApi::class, ExperimentalNativeApi::class, ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.posix.usleep
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
        if (!delay.isFinite() || wait >= Long.MAX_VALUE / 4) return NeverDue
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

    /** Passes the timer thread has made over the queue: one per wake-up, so a test can see that an idle timer does not wake. */
    internal val passes = AtomicInt(0)

    /** Deadlines the timer thread has taken off the inbox so far (see [DeadlineQueue.drainedTotal]). */
    internal val drainedTotal: Long get() = queue.drainedTotal

    private fun run(self: Worker) {
        while (true) {
            while (true) {
                val before = thread.value
                if (before === TimerThread.Closed) return
                if (thread.compareAndSet(before, TimerThread.Awake)) break
            }
            passes.incrementAndGet()
            queue.fireDue(now())
            val sleepUntil =
                when (val next = queue.nextDue()) {
                    is NextDue.At -> {
                        if (next.nanos <= now()) continue
                        next.nanos
                    }
                    NextDue.None -> Long.MAX_VALUE
                }
            val sleeping = TimerThread.Sleeping(sleepUntil)
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
            if (sleepUntil == Long.MAX_VALUE) {
                self.park(PARK_FOREVER_MICROS, true)
            } else {
                when (val step = waitStepFor((sleepUntil - now()) / 1_000L)) {
                    // Too short to be woken for: a nap that cannot be interrupted, then the pass that fires it.
                    is WaitStep.Nap -> if (step.micros > 0) usleep(step.micros.toUInt())
                    is WaitStep.Park -> self.park(step.micros, true)
                }
            }
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

        /** The handle of a deadline that is never due: nothing was scheduled, so there is nothing to disarm. */
        private object NeverDue : DisposableHandle {
            override fun dispose() = Unit
        }
    }
}

private const val PARK_FOREVER_MICROS = 3_600_000_000L

/** A remainder this short is slept, uninterruptibly, rather than parked for. */
private const val SLEEP_THE_REST_MICROS = 200L

/** One wait of the timer thread's, chosen by [waitStepFor]. */
internal sealed interface WaitStep {
    /** An uninterruptible sleep of [micros], after which the thread makes a pass: the last step before a deadline. */
    class Nap(
        val micros: Long,
    ) : WaitStep

    /** A park of [micros] that an earlier deadline being armed can cut short. */
    class Park(
        val micros: Long,
    ) : WaitStep
}

/**
 * The next wait toward a deadline [remainingMicros] away. A park is never asked for the whole remainder, only
 * half of it: a park can return later than asked, and one that overshoots by less than 2x still ends before the
 * deadline, so each pass re-asks for half of what is left, down to a nap. A wait that returns 2x late or more
 * (every primitive does on a hosted macOS runner, see NativeDeadlineTimerLatenessTests) is late whatever this
 * asks for; the halving only guarantees that a modest, proportional overshoot costs nothing.
 */
internal fun waitStepFor(remainingMicros: Long): WaitStep =
    if (remainingMicros <= SLEEP_THE_REST_MICROS) {
        WaitStep.Nap(maxOf(remainingMicros, 0L))
    } else {
        WaitStep.Park(minOf(remainingMicros / 2, PARK_FOREVER_MICROS))
    }

/** What [DeadlineQueue.nextDue] answers: the earliest live deadline, or that there is none. */
internal sealed interface NextDue {
    /** Nothing live is armed. */
    object None : NextDue

    /** The earliest live deadline falls due at [nanos]. */
    class At(
        val nanos: Long,
    ) : NextDue
}

/** What a [DeadlineQueue] holds: [size] nodes in its heap, disarmed ones included, of which [disarmed] are disarmed. */
internal data class HeapCensus(
    val size: Int,
    val disarmed: Int,
)

/**
 * A heap of deadlines owned by one thread, fed lock-free from any. Never blocks and takes the time as an
 * argument: [fireDue] runs, in order of due time and then of arming, every action due at the time it is
 * given. Arming and disarming allocate only the node; the timer thread's own bookkeeping ([nextDue]'s answer,
 * the heap's growth) is off that path.
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

    /** Nodes moved from the inbox to the heap so far. */
    val drainedTotal: Long get() = armed.value

    // Owned by the thread that calls fireDue / nextDue; touched by nothing else.
    private val heap = ArrayList<Node>(INITIAL_CAPACITY)

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
        while (heap.isNotEmpty()) {
            val top = heap[0]
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

    /** The earliest live deadline, after draining the inbox. */
    fun nextDue(): NextDue {
        drain()
        while (heap.isNotEmpty()) {
            val top = heap[0]
            if (top.state.value === NodeState.Disarmed) {
                pop()
                disarmed.decrementAndGet()
                continue
            }
            return NextDue.At(top.dueNanos)
        }
        return NextDue.None
    }

    /** Sweeps disarmed nodes out of the heap when they are most of it, so a burst of cancelled long deadlines is bounded. */
    private fun compact() {
        if (heap.size < COMPACT_MIN || disarmed.value * 2 < heap.size) return
        val before = heap.size
        heap.removeAll { it.state.value === NodeState.Disarmed }
        disarmed.addAndGet(-(before - heap.size))
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

    internal fun census(): HeapCensus = HeapCensus(heap.size, disarmed.value)

    companion object {
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
 * coroutine, not the driver's).
 *
 * **What the caller sees.** A [TimeoutCancellationException] when [timeout] expires: that exception is the
 * public contract (HTTP/3's stalled-stream handling, `withQuicConnection`'s callers and the shared test
 * suites catch it by type), so this is the one place that makes one. kotlinx.coroutines gives that
 * exception no public constructor; the only public way to get one is the one `withTimeout` itself uses for
 * a bound of zero, [timedOut], which throws before it arms any timer. Its message is therefore "Timed out
 * immediately", and the bound that expired is carried as a suppressed [StreamDeadlineBound].
 *
 * **Nesting.** Each call owns its expiry: the scope is cancelled with a [DeadlineExpired] naming the call,
 * and only that call converts it. An outer deadline expiring while an inner one is armed cancels the inner
 * scope with the outer's [DeadlineExpired]; the inner rethrows it and the outer converts it, so the caller
 * of the outer call sees the outer's bound.
 *
 * **The race at the end.** [block] may return at the instant the deadline fires. The expiry cancels the
 * scope; a scope cancelled before it has completed throws, whatever [block] returned, so the caller sees the
 * [TimeoutCancellationException] and the value is dropped. That is what `withTimeout` does (its own
 * documentation: cancellation "may happen at any time, even after the block finishes executing but before
 * the caller gets resumed"), and no worse. What a caller must not lose to it is anything [block] took
 * ownership of before returning: a stream read that took bytes off the stream records them and hands them
 * over from its `catch (TimeoutCancellationException)` (see `QuicheDriver.streamRead`), which this exception
 * reaches exactly as `withTimeout`'s did. A deadline that fires after the scope has completed arms nothing
 * and changes nothing: the handle is disposed when [block] ends.
 *
 * The expiry runs on the timer thread, and `cancel` runs the scope's cancellation handlers there, as the
 * `DefaultExecutor` thread ran them for `withTimeout`. Nothing the driver owns is registered on this scope.
 */
internal actual suspend fun <T> withStreamDeadline(
    timeout: Duration,
    block: suspend CoroutineScope.() -> T,
): T {
    if (timeout <= Duration.ZERO) throw timedOut(timeout)
    val owner = DeadlineOwner()
    return try {
        coroutineScope {
            val scope = this
            val due = NativeDeadlineTimer.Shared.schedule(timeout) { scope.cancel(DeadlineExpired(owner)) }
            try {
                block()
            } finally {
                due.dispose()
            }
        }
    } catch (cancelled: CancellationException) {
        if (cancelled.isExpiryOf(owner)) throw timedOut(timeout) else throw cancelled
    }
}

/** One call of [withStreamDeadline]; the expiry it arms names it, so only it converts that expiry. */
private class DeadlineOwner

/** What an expired [withStreamDeadline] cancels its scope with; never reaches a caller. */
private class DeadlineExpired(
    val owner: DeadlineOwner,
) : CancellationException("a stream deadline expired")

/** The bound that expired, attached to the [TimeoutCancellationException] [withStreamDeadline] throws. */
internal class StreamDeadlineBound(
    val bound: Duration,
) : Throwable("the $bound bound on a stream read or write expired")

private fun CancellationException.isExpiryOf(owner: DeadlineOwner): Boolean {
    var cause: Throwable? = this
    var depth = 0
    while (cause != null && depth < MAX_CAUSE_DEPTH) {
        if (cause is DeadlineExpired && cause.owner === owner) return true
        cause = cause.cause
        depth++
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 8

/** The one construction point of the [TimeoutCancellationException] a [withStreamDeadline] expiry throws. */
private suspend fun timedOut(bound: Duration): TimeoutCancellationException {
    val timeout =
        try {
            withTimeout(Duration.ZERO) {}
            error("a bound of zero returned")
        } catch (e: TimeoutCancellationException) {
            e
        }
    timeout.addSuppressed(StreamDeadlineBound(bound))
    return timeout
}
