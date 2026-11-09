package com.ditchoom.socket.quic

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * The thread every deadline a QUIC connection or server arms on the JVM fires on: one daemon thread,
 * started by [start] before a connection or server exists and never stopped.
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
 * it never starts another: the executor's single core thread does not time out, and a task that throws
 * is captured by its future without killing the thread. A start that fails leaves nothing behind, so
 * the next [start] tries again.
 *
 * The start is eager because a lazy one fails the same way kotlinx's does: the first deadline armed
 * on a timer with no thread starts it, and one armed while that start is failing is queued behind a
 * thread that will never exist. A timer nobody [start]ed — a driver a test builds directly — still
 * starts its thread on the first deadline armed on it.
 *
 * [threadFactory] is a seam for a test that needs the thread start to fail.
 */
internal class DeadlineTimer(
    threadFactory: ThreadFactory = DaemonTimerThreads,
) {
    private val executor =
        ScheduledThreadPoolExecutor(1, threadFactory).apply {
            // Most deadlines are disarmed long before they are due (the driver re-arms its wake on every
            // command); removal on cancel keeps them from accumulating in the queue until then.
            removeOnCancelPolicy = true
        }

    /**
     * Starts the timer thread if it is not already running.
     *
     * @throws DeadlineTimerUnavailableException when the thread cannot be started.
     */
    fun start() {
        try {
            executor.prestartCoreThread()
        } catch (failure: Throwable) {
            throw DeadlineTimerUnavailableException(failure)
        }
    }

    /** [dispatcher], with every `delay`, `withTimeout` and `onTimeout` inside it firing on this timer. */
    fun over(dispatcher: CoroutineDispatcher): CoroutineDispatcher = TimedDispatcher(dispatcher, this)

    /** Runs [block] on the timer thread after [timeMillis], unless the returned handle is disposed first. */
    fun schedule(
        timeMillis: Long,
        block: Runnable,
    ): DisposableHandle {
        if (timeMillis >= NEVER_MILLIS) return NothingScheduled
        val due = executor.schedule(block, timeMillis, TimeUnit.MILLISECONDS)
        return DisposableHandle { due.cancel(false) }
    }

    companion object {
        /** The timer every production connection and server shares. */
        val Shared: DeadlineTimer = DeadlineTimer()

        /**
         * A wait this long is a wait for cancellation: nothing is scheduled, as in kotlinx's own timers
         * (`Duration.INFINITE` arrives as `Long.MAX_VALUE`).
         */
        private const val NEVER_MILLIS = Long.MAX_VALUE / 2_000_000L
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
 * Starts the timer the deadlines armed in this context fire on, when it is a [DeadlineTimer]. A
 * dispatcher a caller supplied instead (a test's virtual-time scheduler) keeps its own timer.
 *
 * @throws DeadlineTimerUnavailableException when the timer thread cannot be started.
 */
internal fun CoroutineContext.startDeadlineTimer() {
    when (val interceptor = this[ContinuationInterceptor]) {
        is TimedDispatcher -> interceptor.timer.start()
        else -> Unit
    }
}

/** [this] dispatcher, with its timers on the same [DeadlineTimer] as [driverContext]'s, when it has one. */
internal fun CoroutineDispatcher.withTimerOf(driverContext: CoroutineContext): CoroutineDispatcher =
    when (val interceptor = driverContext[ContinuationInterceptor]) {
        is TimedDispatcher -> interceptor.timer.over(this)
        else -> this
    }

/**
 * What every JVM/Android driver loop (and its per-path UDP reader loops) runs on: a view of
 * `Dispatchers.IO` as wide as `Dispatchers.Default`, **not** `Dispatchers.Default` itself.
 *
 * kotlinx.coroutines 1.11.0's scheduler can lose the wakeup for a `Dispatchers.Default` task that a
 * thread other than a CPU-permit-holding worker puts in its global CPU queue (kotlinx.coroutines issue
 * #4491; the fix, PR #4528, is unreleased). The worker it unparks cannot take the task without a CPU
 * permit; the worker holding the permit has already made its last scan, gives the permit back and
 * parks. The task then waits, with every worker parked, until some unrelated dispatch wakes one. A
 * driver loop is woken almost only from such threads: the [DeadlineTimer] thread, the NIO receive
 * loops and server handlers on `Dispatchers.IO`, and an application's own threads. On Android, whose
 * core pool is two, a QUIC stream stalled that way for 16 s (#799's flight recorder: `global CPU queue
 * size = 1`, `CPUs acquired = 0`, 18 workers parked).
 *
 * A task dispatched here is a blocking task in the same scheduler, and any worker it unparks runs it
 * whether or not it holds a CPU permit, so no interleaving strands it ([LostCpuWakeupTests] forces the
 * one that strands a `Dispatchers.Default` task). An `IO` view is elastic: it does not share
 * `Dispatchers.IO`'s 64-thread limit, so the selector loops parked in `Dispatchers.IO` cannot starve a
 * driver. Its width is `Dispatchers.Default`'s, so drivers still run no more of quiche's CPU work at
 * once than before.
 */
internal val driverLoopDispatcher: CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(maxOf(2, Runtime.getRuntime().availableProcessors()), "quic-driver")

internal actual val productionDriverContext: CoroutineContext = DeadlineTimer.Shared.over(driverLoopDispatcher)

/** What [DeadlineTimer.schedule] returns for a wait it does not schedule. */
private object NothingScheduled : DisposableHandle {
    override fun dispose() = Unit
}

private object DaemonTimerThreads : ThreadFactory {
    override fun newThread(task: java.lang.Runnable): Thread = Thread(task, "quic-deadline-timer").apply { isDaemon = true }
}
