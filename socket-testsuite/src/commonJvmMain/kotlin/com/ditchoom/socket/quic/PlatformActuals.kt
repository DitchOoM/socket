package com.ditchoom.socket.quic

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

actual fun isAppleKNative(): Boolean = false

actual fun isKotlinNative(): Boolean = false

actual fun quicHarnessAvailability(): QuicHarnessAvailability = QuicHarnessAvailability.Available

actual fun armStallDump(
    after: Duration,
    label: String,
): StallDump {
    val due = StallDumpTimer.schedule({ println(stallDumpOf(label)) }, after.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    return StallDump { due.cancel(false) }
}

private fun stallDumpOf(label: String): String =
    buildString {
        append("STALL-DUMP ").append(label).append('\n')
        for ((thread, stack) in Thread.getAllStackTraces()) {
            append("\"")
                .append(thread.name)
                .append("\" ")
                .append(thread.state)
                .append('\n')
            stack.forEach { append("    at ").append(it).append('\n') }
        }
    }

/** One daemon thread for every pending dump, so none depends on a dispatcher or kotlinx's timer. */
private val StallDumpTimer =
    ScheduledThreadPoolExecutor(1) { task -> Thread(task, "stall-dump").apply { isDaemon = true } }.apply {
        removeOnCancelPolicy = true
    }
