package com.ditchoom.socket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/** How a block run by [withinDeadline] ended: it returned, or its own deadline expired first. */
internal sealed interface WithinDeadline<out T> {
    class Completed<T>(
        val value: T,
    ) : WithinDeadline<T>

    data object Expired : WithinDeadline<Nothing>
}

/**
 * Runs [block] bounded by [deadline]. Only this call's own deadline yields [WithinDeadline.Expired]: when
 * the caller's job was cancelled instead (an enclosing timeout among them), that cancellation is rethrown
 * as the caller's own.
 */
internal suspend fun <T> withinDeadline(
    deadline: Duration,
    block: suspend CoroutineScope.() -> T,
): WithinDeadline<T> =
    try {
        WithinDeadline.Completed(withTimeout(deadline, block))
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        WithinDeadline.Expired
    }
