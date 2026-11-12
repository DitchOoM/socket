package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * `withTimeout`, but its deadline is armed on [DeadlineTimer.Shared] instead of whatever timer the
 * caller's dispatcher has. The caller's is its own: a stream read is called from the application's
 * coroutine, not the driver's, so on `Dispatchers.Default`, `Dispatchers.IO` or `runBlocking` it was
 * kotlinx's `DefaultExecutor`, which every arming woke, and which this library must not depend on (see
 * [DeadlineTimer]). A bound that is armed and disarmed on every read and write of a busy stream now wakes
 * no thread; the timer wakes only for a deadline earlier than the one it already sleeps towards.
 *
 * **What the caller sees.** A [TimeoutCancellationException] when [timeout] expires: that exception is the
 * public contract (HTTP/3's stalled-stream handling, `withQuicConnection`'s callers and the shared test
 * suites catch it by type), so this is the one place that makes one. kotlinx.coroutines gives that
 * exception no public constructor; the only public way to get one is the one `withTimeout` itself uses for
 * a bound of zero, [timedOut], which throws before it arms any timer. Its message is therefore "Timed out
 * immediately", and the bound that expired is carried as a suppressed [StreamDeadlineBound].
 *
 * **Accuracy.** The deadline fires when the timer thread's `parkNanos` returns, as the
 * `ScheduledThreadPoolExecutor` and `DefaultExecutor` it replaces did: at the bound plus scheduling
 * latency, never before it. It is not rounded to a tick.
 *
 * **Nesting.** Each call owns its expiry: the scope is cancelled with a [DeadlineExpired] naming the call,
 * and only that call converts it. An outer deadline expiring while an inner one is armed cancels the inner
 * scope with the outer's [DeadlineExpired]; the inner rethrows it and the outer converts it, so the caller
 * of the outer call sees the outer's bound.
 *
 * **The race at the end.** [block] may return at the instant the deadline fires. The expiry cancels the
 * scope; a scope cancelled before it has completed throws, whatever [block] returned, so the caller sees the
 * [TimeoutCancellationException] and the value is dropped. `withTimeout` does the same for a block that has
 * suspended, and its own documentation says so ("may happen at any time, even after the block finishes
 * executing but before the caller gets resumed"). **One difference:** a block that never suspends and runs past
 * its bound is returned by `withTimeout` (it runs the block undispatched and ignores a timeout that fired
 * during it) and is dropped here. Nothing in the library can lose data to either, because what a block
 * takes ownership of it records before returning and hands over from its `catch (TimeoutCancellationException)`:
 * a read records the chunk it took ([QuicheDriver] `streamRead`: `pendingTaken`, `delivered`), a write the
 * count quiche accepted (`acceptedBytes`), so the exception reaches a handler that returns them. A block
 * that takes ownership without recording it would lose it to the first case as to the second.
 *
 * **Where the bound is armed.** On [DeadlineTimer.Shared], unless the caller's context carries a
 * [StreamDeadlineScheduling], which a test uses to arm the bound on a timer it controls.
 *
 * The expiry runs on the timer thread and `cancel` runs the scope's cancellation handlers there, as the
 * `DefaultExecutor` thread ran them for `withTimeout`. Resuming the caller is dispatched to its own
 * dispatcher as before. Nothing the driver owns is registered on this scope.
 *
 * A bound that is not finite is a wait for cancellation: nothing is armed ([DeadlineTimer.schedule]).
 */
internal actual suspend fun <T> withStreamDeadline(
    timeout: Duration,
    block: suspend CoroutineScope.() -> T,
): T {
    if (timeout <= Duration.ZERO) throw timedOut(timeout)
    val scheduler = currentCoroutineContext()[StreamDeadlineScheduling]?.scheduler ?: SharedScheduler
    val owner = DeadlineOwner()
    return try {
        coroutineScope {
            val scope = this
            val due = scheduler.schedule(timeout.toMillisCeil()) { scope.cancel(DeadlineExpired(owner)) }
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

/** Arms a bound: runs [action] after [millis] unless the returned handle is disposed first. */
internal fun interface StreamDeadlineScheduler {
    fun schedule(
        millis: Long,
        action: Runnable,
    ): DisposableHandle
}

private val SharedScheduler = StreamDeadlineScheduler { millis, action -> DeadlineTimer.Shared.schedule(millis, action) }

/**
 * Puts the bounds armed by [withStreamDeadline] in this context on [scheduler] instead of the shared timer.
 * For a test that must make a deadline fire at an exact point (see `StreamDeadlineRaceTests`); production
 * never installs one.
 */
internal class StreamDeadlineScheduling(
    val scheduler: StreamDeadlineScheduler,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StreamDeadlineScheduling>
}

/** Whole milliseconds, rounded up as kotlinx does for `withTimeout`, so a bound never fires early. */
private fun Duration.toMillisCeil(): Long =
    if (!isFinite()) {
        Long.MAX_VALUE
    } else {
        val nanos = inWholeNanoseconds
        nanos / NANOS_PER_MILLI + if (nanos % NANOS_PER_MILLI > 0) 1 else 0
    }

private const val NANOS_PER_MILLI = 1_000_000L

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
