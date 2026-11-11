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

/** What [DeadlineTimer.schedule] returns for a wait it does not schedule. */
private object NothingScheduled : DisposableHandle {
    override fun dispose() = Unit
}

private object DaemonTimerThreads : ThreadFactory {
    override fun newThread(task: java.lang.Runnable): Thread = Thread(task, "quic-deadline-timer").apply { isDaemon = true }
}
