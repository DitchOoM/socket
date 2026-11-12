package com.ditchoom.socket.quic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * The bound on one stream read or write: `withTimeout`, armed on the platform's shared deadline timer
 * (`DeadlineTimer` on the JVM, `NativeDeadlineTimer` on Kotlin/Native) and not on the caller's dispatcher's.
 * Throws [TimeoutCancellationException] when [timeout] expires, and the expiry cancels [block].
 */
internal expect suspend fun <T> withStreamDeadline(
    timeout: Duration,
    block: suspend CoroutineScope.() -> T,
): T

/** A timer whose deadlines are [DeadlineNode]s the caller makes, so that arming one allocates only the node. */
internal interface NodeTimer {
    val queue: DeadlineQueue

    /** The time, on the timer's clock, [timeoutNanos] from now; never earlier than that. */
    fun dueAfter(timeoutNanos: Long): Long

    /** Publishes [node], made on [queue], and wakes the timer if it is due before the timer's next wake-up. */
    fun arm(node: DeadlineNode)
}

/**
 * [withStreamDeadline] on [timer].
 *
 * - **Exception.** [TimeoutCancellationException], which HTTP/3, `withQuicConnection` and the shared test suites
 *   catch by type. kotlinx.coroutines has no public constructor for it, so [timedOut] takes the one `withTimeout`
 *   throws for a bound of zero, before it arms anything; its message is "Timed out immediately" and the bound that
 *   expired is a suppressed [StreamDeadlineBound] (on the exception, or on its cause once kotlinx's stack-trace
 *   recovery has copied it).
 * - **Nesting.** Each call's expiry cancels its scope with a [DeadlineExpired] naming that call, and only that call
 *   converts it: an outer bound expiring cancels the inner scope with the outer's [DeadlineExpired], which the
 *   inner rethrows and the outer converts.
 * - **The end.** A block that returns after its deadline cancelled the scope is dropped and the caller gets the
 *   timeout. What a block took ownership of it records before returning and hands over from its
 *   `catch (TimeoutCancellationException)` (a read's `pendingTaken` and `delivered`, a write's `acceptedBytes`). A
 *   block that never suspends and runs past its bound loses its value too, where `withTimeout` would have returned it.
 * - **Thread.** The expiry cancels the scope on the timer thread, so cancellation handlers of the block's children
 *   run there and must not block. Resuming the caller is dispatched to its own dispatcher.
 * - **Seam.** A [StreamDeadlineScheduling] in the caller's context arms the bound on a scheduler a test controls.
 *
 * A bound that is not finite arms nothing.
 */
internal suspend fun <T> withDeadlineOn(
    timer: NodeTimer,
    timeout: Duration,
    block: suspend CoroutineScope.() -> T,
): T {
    if (timeout <= Duration.ZERO) timedOut(timeout)
    val nanos = timeout.inWholeNanoseconds
    if (!timeout.isFinite() || nanos >= NEVER_NANOS) return coroutineScope(block)
    val placement = currentCoroutineContext().boundPlacement()
    val bound = StreamBound(timer.queue, timer.dueAfter(nanos))
    return try {
        coroutineScope {
            bound.bind(this)
            val elsewhere: DisposableHandle =
                when (placement) {
                    BoundPlacement.OnTheTimer -> {
                        timer.arm(bound)
                        Unscheduled
                    }
                    is BoundPlacement.OnAScheduler -> placement.scheduler.schedule(nanos.ceilToMillis()) { bound.expire() }
                }
            try {
                block()
            } finally {
                elsewhere.dispose()
                bound.disarm()
            }
        }
    } catch (cancelled: CancellationException) {
        if (bound.expired(cancelled)) timedOut(timeout) else throw cancelled
    }
}

/** Where a bound is armed. */
private sealed interface BoundPlacement {
    /** On the platform's shared timer. */
    object OnTheTimer : BoundPlacement

    /** On the scheduler a [StreamDeadlineScheduling] in the caller's context names. */
    class OnAScheduler(
        val scheduler: StreamDeadlineScheduler,
    ) : BoundPlacement
}

private fun CoroutineContext.boundPlacement(): BoundPlacement =
    this[StreamDeadlineScheduling]?.let { BoundPlacement.OnAScheduler(it.scheduler) } ?: BoundPlacement.OnTheTimer

/** What a bound armed on the shared timer has to dispose elsewhere: nothing, the bound is its own handle. */
private object Unscheduled : DisposableHandle {
    override fun dispose() = Unit
}

/** A bound longer than this is a wait for cancellation: nothing is armed. */
private const val NEVER_NANOS = Long.MAX_VALUE / 4

private const val NANOS_PER_MILLI = 1_000_000L

/** [this] in whole milliseconds, rounded up, so a bound never fires early. */
internal fun Long.ceilToMillis(): Long = this / NANOS_PER_MILLI + if (this % NANOS_PER_MILLI > 0) 1 else 0

/**
 * The deadline of one [withDeadlineOn] call; on expiry it cancels the call's scope. It is the expiry's owner too, so
 * a call recognises its own expiry by identity. Holds the scope from [bind] until it is disarmed.
 */
private class StreamBound(
    queue: DeadlineQueue,
    dueNanos: Long,
) : DeadlineNode(queue, dueNanos) {
    private var scope: CoroutineScope = FinishedScope

    /** Before the bound is armed, which is the only time the timer thread can reach [scope]. */
    fun bind(scope: CoroutineScope) {
        this.scope = scope
    }

    override fun fire() {
        scope.cancel(DeadlineExpired(this))
    }

    /** Fires the expiry from a scheduler that is not the timer's. */
    fun expire() {
        fireIfArmed()
    }

    // A scope that has finished is not kept: its caller's frames would stay reachable until the deadline fell due.
    override fun release() {
        scope = FinishedScope
    }

    fun expired(cancelled: CancellationException): Boolean =
        generateSequence<Throwable>(cancelled) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .any { it is DeadlineExpired && it.owner === this }
}

/** A scope with nothing left to cancel. */
private object FinishedScope : CoroutineScope {
    override val coroutineContext: CoroutineContext = Job().apply { cancel() }
}

/** What an expired bound cancels its scope with; never reaches a caller. */
private class DeadlineExpired(
    val owner: Any,
) : CancellationException("a stream deadline expired")

/** The bound that expired, attached to the [TimeoutCancellationException] a bound throws. */
internal class StreamDeadlineBound(
    val bound: Duration,
) : Throwable("the $bound bound on a stream read or write expired")

private const val MAX_CAUSE_DEPTH = 8

/** Throws the [TimeoutCancellationException] for [bound], made the one way kotlinx.coroutines allows: a bound of zero. */
private suspend fun timedOut(bound: Duration): Nothing {
    try {
        withTimeout(Duration.ZERO) { awaitCancellation() }
    } catch (timeout: TimeoutCancellationException) {
        timeout.addSuppressed(StreamDeadlineBound(bound))
        throw timeout
    }
}

/** Arms a bound: runs [action] after [millis] unless the returned handle is disposed first. */
internal fun interface StreamDeadlineScheduler {
    fun schedule(
        millis: Long,
        action: Runnable,
    ): DisposableHandle
}

/** Arms the bounds of [withStreamDeadline] in this context on [scheduler], so a test can fire a deadline at an exact point. */
internal class StreamDeadlineScheduling(
    val scheduler: StreamDeadlineScheduler,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StreamDeadlineScheduling>
}
