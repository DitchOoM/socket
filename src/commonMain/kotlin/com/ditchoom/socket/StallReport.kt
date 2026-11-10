package com.ditchoom.socket

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [block] once. If it is still running after [STALL_REPORT_AFTER], reports one `[stall]` line
 * naming [waitingOn] and [state]; [block] is never interrupted or restarted.
 */
internal suspend fun <T> reportingStall(
    waitingOn: String,
    state: () -> String,
    report: (String) -> Unit = { println(it) },
    block: suspend () -> T,
): T =
    coroutineScope {
        val watchdog =
            launch {
                delay(STALL_REPORT_AFTER)
                report("[stall] still waiting after $STALL_REPORT_AFTER on $waitingOn: ${state()}")
            }
        try {
            block()
        } finally {
            watchdog.cancel()
        }
    }

internal val STALL_REPORT_AFTER = 5.seconds
