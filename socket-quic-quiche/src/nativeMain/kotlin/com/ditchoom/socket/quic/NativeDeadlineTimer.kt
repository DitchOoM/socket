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
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The thread every QUIC deadline on Kotlin/Native fires on, over one [DeadlineQueue]: arming is a lock-free push of a
 * [DeadlineNode], disarming one compare-and-set. kotlinx.coroutines' own timer for `Dispatchers.Default` is one worker
 * that every arming takes a lock on, and it keeps the entry of a disarmed deadline until it falls due.
 *
 * The thread is woken only when a new deadline is earlier than the one it sleeps towards. A far-off disarmed deadline
 * stays queued and the thread keeps sleeping towards it, so the next arming, which is later, wakes nothing; a near one
 * is dropped at once ([DeadlineQueue.nextDue]). When disarmed deadlines are most of the queue the arming that makes
 * them so asks the thread for a sweep ([DeadlineQueue.needsSweep]).
 *
 * A pass that throws is reported and followed by another after a short pause, so a failure does not end the thread. A
 * `Worker` cannot be restarted once it has terminated, so this guards a `Throwable` that reaches the loop and nothing
 * that terminates the process.
 */
internal class NativeDeadlineTimer private constructor(
    override val queue: DeadlineQueue,
) : NodeTimer {
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

        /** Parked with nothing queued, until woken. */
        object Idle : TimerThread

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
        return queue.node(dueAfter(maxOf(wait, 0L)), action).also(::arm)
    }

    override fun dueAfter(timeoutNanos: Long): Long = now() + timeoutNanos

    override fun arm(node: DeadlineNode) {
        queue.arm(node)
        if (queue.needsSweep()) wakeToSweep() else wakeIfEarlier(node.dueNanos)
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
                TimerThread.Idle ->
                    if (thread.compareAndSet(TimerThread.Idle, TimerThread.Awake)) {
                        worker.executeAfter(0L) {}
                        return
                    }
            }
        }
    }

    /** Wakes the thread, whatever it sleeps towards, to drop the disarmed deadlines that have piled up. */
    private fun wakeToSweep() {
        while (true) {
            when (val state = thread.value) {
                TimerThread.Awake, TimerThread.Closed -> return
                is TimerThread.Sleeping, TimerThread.Idle ->
                    if (thread.compareAndSet(state, TimerThread.Awake)) {
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

    /** Deadlines held, disarmed ones not yet dropped included, so a test can see the queue stays bounded. */
    internal val queued: Int get() = queue.size

    /** Whether the timer thread is waiting (until a deadline, or with nothing queued) and will not pass until woken. */
    internal val isParked: Boolean get() = thread.value.let { it is TimerThread.Sleeping || it === TimerThread.Idle }

    /**
     * The timer thread's life, for as long as the timer is open. A pass that throws anything is reported (to the
     * error stream: Kotlin/Native has no per-thread handler to hand it to) and followed by another after a short
     * pause, so a failure does not end the thread, which every deadline in the process depends on; the queue keeps
     * what a failed pass had taken off its inbox ([DeadlineQueue]). What a `Worker` cannot do: it cannot be
     * restarted once it has terminated, and a failure that terminates the process (an allocation the runtime
     * cannot satisfy) is not catchable, so what this guards is a `Throwable` that reaches the loop.
     */
    private fun run(self: Worker) {
        while (pass(self)) {
            // pass() answers false only when the timer was closed.
        }
    }

    /** One pass: fire what is due, then wait. False once the timer is closed. */
    private fun pass(self: Worker): Boolean {
        try {
            while (true) {
                val before = thread.value
                if (before === TimerThread.Closed) return false
                if (thread.compareAndSet(before, TimerThread.Awake)) break
            }
            passes.incrementAndGet()
            queue.fireDue(now())
            val sleeping =
                when (val next = queue.nextDue(now())) {
                    is NextDue.At -> {
                        if (next.nanos <= now()) return true
                        TimerThread.Sleeping(next.nanos)
                    }
                    NextDue.None -> TimerThread.Idle
                }
            if (!thread.compareAndSet(TimerThread.Awake, sleeping)) {
                // Closed, or woken between the pass and here: another pass.
                return thread.value !== TimerThread.Closed
            }
            // An arm that raced the compare-and-set above saw Awake and did not wake us; it pushed first.
            if (queue.hasInbox()) {
                thread.compareAndSet(sleeping, TimerThread.Awake)
                return true
            }
            when (sleeping) {
                TimerThread.Idle -> self.park(PARK_FOREVER_MICROS, true)
                is TimerThread.Sleeping ->
                    when (val step = waitStepFor((sleeping.dueNanos - now()) / 1_000L)) {
                        // Too short to be woken for: a nap that cannot be interrupted, then the pass that fires it.
                        is WaitStep.Nap -> if (step.micros > 0) usleep(step.micros.toUInt())
                        is WaitStep.Park -> self.park(step.micros, true)
                    }
                else -> Unit
            }
            return true
        } catch (failure: Throwable) {
            if (thread.value === TimerThread.Closed) return false
            thread.value = TimerThread.Awake
            reportOnThisThread(failure)
            usleep(FAILED_PASS_PAUSE_MICROS.toUInt())
            return true
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

/** The pause after a pass that failed, before the next. */
private const val FAILED_PASS_PAUSE_MICROS = 50_000L

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

internal actual fun reportOnThisThread(failure: Throwable) {
    try {
        failure.printStackTrace()
    } catch (_: Throwable) {
        // Nothing left to tell: the timer thread must survive a failure to report.
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

internal actual suspend fun <T> withStreamDeadline(
    timeout: Duration,
    block: suspend CoroutineScope.() -> T,
): T = withDeadlineOn(NativeDeadlineTimer.Shared, timeout, block)
