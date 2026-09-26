package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

/**
 * Runs [body] in `runBlocking([context])` on a thread of its own and fails if it has neither returned
 * nor thrown within [budget].
 *
 * The bound is a JVM wait on that thread's result, not a coroutine timer, so it holds when every
 * coroutine deadline in the process has stopped firing — a wedge a `withTimeout` budget cannot report,
 * because it is one of the deadlines that stopped. The failure carries the body thread's stack and
 * whether kotlinx's shared timer thread exists at that moment.
 */
internal fun <T> failIfWedged(
    budget: Duration,
    label: String,
    context: CoroutineContext = EmptyCoroutineContext,
    body: suspend CoroutineScope.() -> T,
): T {
    val outcome = CompletableFuture<T>()
    val runner =
        Thread({
            try {
                outcome.complete(runBlocking(context, body))
            } catch (failure: Throwable) {
                outcome.completeExceptionally(failure)
            }
        }, "wedge-watchdog/$label").apply {
            isDaemon = true
            start()
        }
    try {
        return outcome.get(budget.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    } catch (failed: ExecutionException) {
        throw failed.cause ?: failed
    } catch (_: TimeoutException) {
        val stack = runner.stackTrace.joinToString("\n    at ")
        val sharedTimer = Thread.getAllStackTraces().keys.filter { it.name.startsWith(KOTLINX_SHARED_TIMER_THREAD) }
        runner.interrupt()
        throw AssertionError(
            "$label made no progress and no deadline ended it within $budget: every bounded operation " +
                "inside it stayed unbounded. kotlinx's shared timer thread: " +
                (if (sharedTimer.isEmpty()) "absent" else sharedTimer.joinToString { "${it.state}" }) +
                ". The body's thread:\n    at $stack",
        )
    }
}

/**
 * The name of the thread kotlinx.coroutines fires timers on for a dispatcher that has no timer of its
 * own. Debug mode appends the running coroutine's name to it.
 */
internal const val KOTLINX_SHARED_TIMER_THREAD = "kotlinx.coroutines.DefaultExecutor"
