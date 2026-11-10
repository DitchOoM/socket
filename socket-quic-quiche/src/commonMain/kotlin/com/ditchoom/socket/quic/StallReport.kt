package com.ditchoom.socket.quic

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [wait] and, if it is still pending after [STALL_REPORT_AFTER] while the caller is still active,
 * prints one `[stall]` line naming [waitingOn] and [state], then runs [wait] again so the wait itself
 * is unchanged. The normal path pays for one timeout and prints nothing.
 */
internal suspend inline fun <T> reportingStall(
    waitingOn: String,
    crossinline state: () -> String,
    crossinline wait: suspend () -> T,
): T =
    try {
        withTimeout(STALL_REPORT_AFTER) { wait() }
    } catch (e: TimeoutCancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        println("[stall] still waiting after $STALL_REPORT_AFTER on $waitingOn: ${state()}")
        wait()
    }

internal val STALL_REPORT_AFTER = 5.seconds
