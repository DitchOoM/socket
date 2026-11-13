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
 * The thread that fires the deadlines of a QUIC connection's or server's application-facing side (its scope,
 * [applicationScopeDispatcher], and the server's handlers, [ServerHandlerThreads]) and every bound on a
 * stream read or write ([withStreamDeadline]). One daemon thread, never stopped, over one [DeadlineQueue].
 * The transport's own deadlines fire on the event loop the connection runs on ([quicLoopGroup]).
 *
 * kotlinx.coroutines' timer for a dispatcher without one (`Dispatchers.Default`, `Dispatchers.IO`) is
 * `DefaultExecutor`, a thread that can fail to restart and then fires nothing for the life of the process; nothing
 * the library arms depends on it.
 *
 * [start] runs before a connection or server exists, so a process that cannot start the thread refuses the
 * connect or bind with [DeadlineTimerUnavailableException]; a start that fails leaves nothing behind and the next
 * [start] tries again. A timer nobody started starts its thread on the first deadline armed on it.
 *
 * Arming is a lock-free push of a [DeadlineNode] and disarming one compare-and-set. The thread is woken only for
 * a deadline due before the one it sleeps towards, so a bound armed and disarmed on every operation of a busy
 * stream wakes nothing. A pass that throws is reported to the thread's uncaught-exception handler and followed
 * by another, so the thread does not die.
 *
 * [threadFactory] and [clock] are seams for tests that fail the thread start or a pass.
 */
internal class DeadlineTimer(
    private val threadFactory: ThreadFactory = DaemonTimerThreads,
    private val clock: () -> Long = System::nanoTime,
) : NodeTimer {
    /** Whether the timer thread exists. Only one caller claims the start. */
    private sealed interface Lifecycle {
        object NotStarted : Lifecycle

        /** One caller is creating the thread; the others wait for it. */
        object Starting : Lifecycle

        class Running(
            val thread: Thread,
        ) : Lifecycle
    }

    /** What the timer thread is doing, for the arming side to decide whether it has to wake it. */
    private sealed interface TimerThread {
        /** Running a pass over the queue: it sees anything armed before its next sleep. */
        object Awake : TimerThread

        /** Parked until [dueNanos] (on [origin]'s clock) unless woken. */
        class Sleeping(
            val dueNanos: Long,
        ) : TimerThread

        /** Parked with nothing to wait for, until woken. */
        object Idle : TimerThread
    }

    override val queue = DeadlineQueue()
    private val origin = clock()
    private val lifecycle = AtomicReference<Lifecycle>(Lifecycle.NotStarted)
    private val state = AtomicReference<TimerThread>(TimerThread.Awake)

    private fun now(): Long = clock() - origin

    /** Passes the timer thread has made over the queue, one per wake-up; for a test that counts wake-ups. */
    internal val passes = AtomicInteger(0)

    /** Deadlines held, disarmed ones not yet dropped included; for a test that checks the queue stays bounded. */
    internal val queued: Int get() = queue.size

    /** Whether the timer thread is parked and will not pass until woken; for a test that counts from rest. */
    internal val isParked: Boolean get() = state.get() !== TimerThread.Awake

    /**
     * Starts the timer thread if it is not running, and returns it.
     *
     * @throws DeadlineTimerUnavailableException when the thread cannot be started.
     */
    fun start(): Thread {
        while (true) {
            when (val current = lifecycle.get()) {
                is Lifecycle.Running -> return current.thread
                Lifecycle.Starting -> Thread.onSpinWait()
                Lifecycle.NotStarted -> if (lifecycle.compareAndSet(Lifecycle.NotStarted, Lifecycle.Starting)) return launch()
            }
        }
    }

    private fun launch(): Thread =
        try {
            val thread = threadFactory.newThread(::run)
            thread.start()
            lifecycle.set(Lifecycle.Running(thread))
            thread
        } catch (failure: Throwable) {
            lifecycle.set(Lifecycle.NotStarted)
            throw DeadlineTimerUnavailableException(failure)
        }

    /** [dispatcher], with every `delay`, `withTimeout` and `onTimeout` inside it firing on this timer. */
    fun over(dispatcher: CoroutineDispatcher): CoroutineDispatcher = TimedDispatcher(dispatcher, this)

    /** Runs [block] on the timer thread after [timeMillis], unless the returned handle is disposed first. */
    fun schedule(
        timeMillis: Long,
        block: Runnable,
    ): DisposableHandle = if (timeMillis >= NEVER_MILLIS) NothingScheduled else scheduleNode(timeMillis, block)

    /** [schedule] for a bound that is due: the node, so a test can see whether its disarm won. */
    internal fun scheduleNode(
        timeMillis: Long,
        block: Runnable,
    ): DeadlineNode = queue.node(now() + maxOf(timeMillis, 0L) * NANOS_PER_MILLI, block).also(::arm)

    override fun dueAfter(timeoutNanos: Long): Long = now() + timeoutNanos.ceilToMillis() * NANOS_PER_MILLI

    override fun arm(node: DeadlineNode) {
        // A timer nobody started (a driver a test builds directly) starts its thread on the first deadline.
        val thread = start()
        queue.arm(node)
        if (queue.needsSweep()) wakeToSweep(thread) else wakeIfEarlier(node.dueNanos, thread)
    }

    /** Wakes the thread, whatever it sleeps towards, to drop the disarmed deadlines that have piled up. */
    private fun wakeToSweep(thread: Thread) {
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

    private fun wakeIfEarlier(
        due: Long,
        thread: Thread,
    ) {
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
        // Nothing here is interruptible; a stray interrupt (a cancellation handler on this thread may re-assert one)
        // would make every park return at once.
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

        /** A wait this long is a wait for cancellation: nothing is scheduled, as in kotlinx's timers. */
        private const val NEVER_MILLIS = Long.MAX_VALUE / 2_000_000L

        private const val NANOS_PER_MILLI = 1_000_000L

        private const val FAILED_PASS_PAUSE_NANOS = 50_000_000L
    }
}

/** What [DeadlineTimer.schedule] returns for a wait it does not schedule. */
private object NothingScheduled : DisposableHandle {
    override fun dispose() = Unit
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
