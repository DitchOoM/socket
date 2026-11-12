package com.ditchoom.socket.quic

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * The thread the deadlines of a QUIC connection's or server's application-facing side fire on: its
 * scope ([applicationScopeDispatcher]) and the server's handlers ([serverReceiveDispatcher]). One daemon
 * thread, started by [start] before a connection or server exists and never stopped.
 *
 * The transport's own deadlines (each driver's wake, the handshake bound, a server's receive loop) fire
 * on the event loop the connection runs on ([quicLoopGroup]), not here. A test that passes a
 * [DeadlineTimer]-timed dispatcher as its driver context ([over]) still runs its drivers on it.
 *
 * kotlinx.coroutines' own timer for a dispatcher without one (`Dispatchers.Default`, `Dispatchers.IO`)
 * is `DefaultExecutor`, whose thread exits after a second with nothing scheduled and is started again
 * by the next `delay`, `withTimeout` or `select`/`onTimeout`. It records the new thread before starting
 * it, so a start that throws — `OutOfMemoryError: unable to create native thread` under native-memory
 * or thread exhaustion — leaves it holding a thread that never runs, and no timer on it fires again for
 * the life of the process. Nothing the library arms may depend on that thread.
 *
 * This timer's thread is started once, at establishment, where a failure is a typed refusal
 * ([DeadlineTimerUnavailableException]) rather than a connection whose timers cannot fire. After that
 * it never starts another: the thread does not time out, and an action that throws is handed to the
 * thread's uncaught-exception handler without ending it. A start that fails leaves nothing behind, so
 * the next [start] tries again.
 *
 * The start is eager because a lazy one fails the same way kotlinx's does: the first deadline armed
 * on a timer with no thread starts it, and one armed while that start is failing is queued behind a
 * thread that will never exist. A timer nobody [start]ed — a driver a test builds directly — still
 * starts its thread on the first deadline armed on it.
 *
 * Also the timer of every bound on a stream read or write ([withStreamDeadline]): a deadline armed and
 * disarmed on each operation of a busy stream must not wake the thread, see [schedule].
 *
 * [threadFactory] is a seam for a test that needs the thread start to fail.
 */
internal class DeadlineTimer(
    private val threadFactory: ThreadFactory = DaemonTimerThreads,
    /** Monotonic nanoseconds; a seam for a test that needs a pass on the timer thread to fail. */
    private val clock: () -> Long = System::nanoTime,
) {
    /** What the timer thread is doing, for the arming side to decide whether it has to wake it. */
    private sealed interface TimerThread {
        /** Running a pass over the queue: it sees anything armed before its next sleep, so arming need not wake it. */
        object Awake : TimerThread

        /** Parked until [dueNanos] (on [origin]'s clock) unless woken. */
        class Sleeping(
            val dueNanos: Long,
        ) : TimerThread

        /** Parked with nothing armed, until woken. */
        object Idle : TimerThread
    }

    private val queue = DeadlineQueue()
    private val origin = clock()
    private val state = AtomicReference<TimerThread>(TimerThread.Awake)

    @Volatile
    private var thread: Thread? = null

    /** Nanoseconds since this timer was made; a monotonic clock that cannot wrap in any process's life. */
    private fun now(): Long = clock() - origin

    /** Passes the timer thread has made over the queue: one per wake-up, so a test can see an idle timer does not wake. */
    internal val passes = AtomicInteger(0)

    /** Deadlines held, disarmed ones not yet dropped included, so a test can see the queue stays bounded. */
    internal val queued: Int get() = queue.size

    /** Whether the timer thread is parked (until a deadline, or with nothing armed) and so will not pass until woken. */
    internal val isParked: Boolean get() = state.get() !== TimerThread.Awake

    /**
     * Starts the timer thread if it is not already running.
     *
     * @throws DeadlineTimerUnavailableException when the thread cannot be started.
     */
    fun start() {
        if (thread != null) return
        synchronized(this) {
            if (thread != null) return
            try {
                val started = threadFactory.newThread(::run)
                started.start()
                thread = started
            } catch (failure: Throwable) {
                throw DeadlineTimerUnavailableException(failure)
            }
        }
    }

    /** [dispatcher], with every `delay`, `withTimeout` and `onTimeout` inside it firing on this timer. */
    fun over(dispatcher: CoroutineDispatcher): CoroutineDispatcher = TimedDispatcher(dispatcher, this)

    /**
     * Runs [block] on the timer thread after [timeMillis], unless the returned handle is disposed first.
     *
     * Arming is a lock-free push of one node; disposing is one compare-and-set on it. The thread is woken
     * only when this deadline is earlier than the one it is sleeping towards (or it has nothing to sleep
     * towards), which a bound re-armed on every read and write of a stream, each a little later than the
     * stale one it replaces, never is: such a bound costs no wake-up, however many are armed and disarmed.
     * A disarmed deadline stays in the queue: the thread drops it when it falls due, or a sweep does once
     * [DeadlineQueue.COMPACT_MIN] of them are most of the queue (the arming that makes it so wakes the thread
     * for that), so a stale bound costs one pass at its own due time and the queue stays bounded.
     */
    fun schedule(
        timeMillis: Long,
        block: Runnable,
    ): DeadlineQueue.Node {
        if (timeMillis >= NEVER_MILLIS) return NothingScheduled
        // A timer nobody started (a driver a test builds directly) starts its thread on the first deadline.
        start()
        val due = now() + maxOf(timeMillis, 0L) * NANOS_PER_MILLI
        val node = queue.arm(due, block)
        if (queue.needsSweep()) wakeToSweep() else wakeIfEarlier(due)
        return node
    }

    /** Wakes the thread, whatever it sleeps towards, to drop the disarmed deadlines that have piled up. */
    private fun wakeToSweep() {
        while (true) {
            when (val current = state.get()) {
                TimerThread.Awake -> return
                is TimerThread.Sleeping, TimerThread.Idle ->
                    if (state.compareAndSet(current, TimerThread.Awake)) {
                        LockSupport.unpark(thread)
                        return
                    }
            }
        }
    }

    private fun wakeIfEarlier(due: Long) {
        while (true) {
            when (val current = state.get()) {
                TimerThread.Awake -> return
                is TimerThread.Sleeping ->
                    if (due >= current.dueNanos) {
                        return
                    } else if (state.compareAndSet(current, TimerThread.Awake)) {
                        LockSupport.unpark(thread)
                        return
                    }
                TimerThread.Idle ->
                    if (state.compareAndSet(TimerThread.Idle, TimerThread.Awake)) {
                        LockSupport.unpark(thread)
                        return
                    }
            }
        }
    }

    /**
     * The timer thread's life: passes over the queue, for as long as the process runs. A pass that throws
     * anything (an `OutOfMemoryError` growing the heap, a clock that fails) is reported to the thread's
     * uncaught-exception handler and followed by another after a short pause: the thread must not die,
     * because every bound in the process depends on it and a dead one never fires again, so each read and
     * write would wait forever. The queue keeps what a failed pass had taken off its inbox ([DeadlineQueue]).
     */
    private fun run() {
        while (true) {
            try {
                pass()
            } catch (failure: Throwable) {
                state.set(TimerThread.Awake)
                reportOnThisThread(failure)
                LockSupport.parkNanos(FAILED_PASS_PAUSE_NANOS)
            }
        }
    }

    private fun pass() {
        // A stray interrupt (a cancellation handler that re-asserts one runs on this thread) would make every
        // park return at once and the loop spin; nothing here is interruptible, so it is cleared and ignored.
        Thread.interrupted()
        state.set(TimerThread.Awake)
        passes.incrementAndGet()
        queue.fireDue(now())
        val sleeping =
            when (val next = queue.nextDue(now())) {
                is NextDue.At -> {
                    if (next.nanos <= now()) return
                    TimerThread.Sleeping(next.nanos)
                }
                NextDue.None -> TimerThread.Idle
            }
        if (!state.compareAndSet(TimerThread.Awake, sleeping)) return
        // An arm that raced the compare-and-set above saw Awake and did not wake us; it pushed first.
        if (queue.hasInbox()) {
            state.compareAndSet(sleeping, TimerThread.Awake)
            return
        }
        when (sleeping) {
            TimerThread.Idle -> LockSupport.park(this)
            is TimerThread.Sleeping -> LockSupport.parkNanos(this, sleeping.dueNanos - now())
            TimerThread.Awake -> Unit
        }
    }

    companion object {
        /** The timer every production connection and server shares. */
        val Shared: DeadlineTimer = DeadlineTimer()

        /**
         * A wait this long is a wait for cancellation: nothing is scheduled, as in kotlinx's own timers
         * (`Duration.INFINITE` arrives as `Long.MAX_VALUE`).
         */
        private const val NEVER_MILLIS = Long.MAX_VALUE / 2_000_000L

        private const val NANOS_PER_MILLI = 1_000_000L

        private const val FAILED_PASS_PAUSE_NANOS = 50_000_000L

        /** What [schedule] returns for a wait it does not schedule: a deadline that is already disarmed, on no timer's queue. */
        private val NothingScheduled: DeadlineQueue.Node =
            DeadlineQueue().arm(Long.MAX_VALUE) { }.also { it.disarm() }
    }
}

/**
 * Thrown by a QUIC connect or bind on the JVM when the thread its deadlines fire on cannot be started —
 * a process out of native threads or memory. Nothing is opened: a connection whose idle, loss-recovery
 * and handshake timers could never fire would hang instead of failing. [cause] is what starting the
 * thread threw.
 */
class DeadlineTimerUnavailableException(
    cause: Throwable,
) : IllegalStateException("the thread QUIC deadlines fire on could not be started", cause)

/** Dispatches through [delegate]; every timer armed in it fires on [timer]. */
@OptIn(InternalCoroutinesApi::class)
private class TimedDispatcher(
    private val delegate: CoroutineDispatcher,
    val timer: DeadlineTimer,
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
        val due = timer.schedule(timeMillis) { continuation.resume(Unit) }
        continuation.invokeOnCancellation { due.dispose() }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle = timer.schedule(timeMillis, block)

    override fun toString(): String = "$delegate+DeadlineTimer"
}

/**
 * Makes sure the deadlines armed for a connection or server built on [this] driver context can fire,
 * before anything is opened, so a process out of threads refuses the connect or bind with a typed
 * [DeadlineTimerUnavailableException] instead of opening something whose timers never fire:
 *
 * - The production placement ([productionDriverContext]): a loop of the QUIC loop group can run (one
 *   is started and left to linger, so the driver's own placement finds a running loop), and
 *   [DeadlineTimer.Shared], which times the connection's application-facing scope, is started.
 * - A [DeadlineTimer]-timed dispatcher (a test's): its timer thread is started.
 * - Anything else (a test's virtual-time scheduler) keeps its own timer.
 *
 * @throws DeadlineTimerUnavailableException when a thread cannot be started.
 */
internal fun CoroutineContext.startTimers() {
    if (this[LoopPlacement] != null) {
        placeDriver(this).release()
        DeadlineTimer.Shared.start()
        return
    }
    when (val interceptor = this[ContinuationInterceptor]) {
        is TimedDispatcher -> interceptor.timer.start()
        else -> Unit
    }
}

/**
 * The dispatcher a connection's or server's application-facing scope runs on: `Dispatchers.IO`, never a
 * loop, because what runs there is (or waits on) the application's code. Its timers fire on
 * [DeadlineTimer.Shared] in production, on the test's [DeadlineTimer] when [driverContext] is timed by
 * one, and on kotlinx's own otherwise.
 */
internal fun applicationScopeDispatcher(driverContext: CoroutineContext): CoroutineDispatcher =
    when {
        driverContext[LoopPlacement] != null -> DeadlineTimer.Shared.over(Dispatchers.IO)
        else ->
            when (val interceptor = driverContext[ContinuationInterceptor]) {
                is TimedDispatcher -> interceptor.timer.over(Dispatchers.IO)
                else -> Dispatchers.IO
            }
    }

private object DaemonTimerThreads : ThreadFactory {
    override fun newThread(task: java.lang.Runnable): Thread = Thread(task, "quic-deadline-timer").apply { isDaemon = true }
}

/** Hands [failure] to this thread's uncaught-exception handler; a handler that throws is ignored, it cannot be reported to. */
internal actual fun reportOnThisThread(failure: Throwable) {
    val thread = Thread.currentThread()
    try {
        // Nullable on Android (a thread that has ended has none); the default handler is the next resort.
        (thread.uncaughtExceptionHandler ?: Thread.getDefaultUncaughtExceptionHandler())?.uncaughtException(thread, failure)
    } catch (_: Throwable) {
        // Nothing left to tell: the handler is the last resort, and the timer thread must survive it.
    }
}
