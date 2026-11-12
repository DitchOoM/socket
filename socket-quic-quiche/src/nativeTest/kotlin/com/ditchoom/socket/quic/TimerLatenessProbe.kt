package com.ditchoom.socket.quic

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Measures how late a deadline armed in a driver's context fires and resumes its coroutine, while the
 * perf workload runs: [run] repeatedly `delay`s [TICK] in the context it is launched in and records the time
 * the delay took beyond [TICK]. Loss recovery rides on timers firing on time, so this is the number that
 * says whether the timer in use keeps up under load; run the same probe in a build whose production context
 * is kotlinx's own timer to compare. Not an assertion: lateness depends on the machine's load.
 */
internal class TimerLatenessProbe {
    private val samplesMicros = ArrayList<Long>(1 shl 16)

    suspend fun run() {
        while (coroutineContext.isActive) {
            val start = TimeSource.Monotonic.markNow()
            delay(TICK)
            samplesMicros.add(maxOf((start.elapsedNow() - TICK).inWholeMicroseconds, 0L))
        }
    }

    /** `PERF-LATENESS n=.. p50_us=.. p99_us=.. p999_us=.. max_us=..` over what [run] recorded; call after it ended. */
    fun render(): String {
        val sorted = samplesMicros.sorted()
        if (sorted.isEmpty()) return "PERF-LATENESS n=0"

        fun at(q: Double) = sorted[minOf((sorted.size * q).toInt(), sorted.size - 1)]
        return "PERF-LATENESS tick_ms=${TICK.inWholeMilliseconds} n=${sorted.size} p50_us=${at(0.5)} p99_us=${at(0.99)} " +
            "p999_us=${at(0.999)} max_us=${sorted.last()}"
    }

    private companion object {
        val TICK = 2.milliseconds
    }
}
