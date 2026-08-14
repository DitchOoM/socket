package com.ditchoom.socket.quic

import java.lang.management.ManagementFactory

/**
 * Capture JVM state — heap stats, thread counts, stack traces of every
 * relevant thread — and return it as a multi-line string.
 *
 * Returned (not printed) so it can be embedded in an exception message:
 * Gradle's `socket-quic` testLogging block uses `events("failed","skipped")`
 * with `showCauses = showStackTraces = true`, which means *exception
 * messages and stack traces always appear in the build log* but **stdout
 * does not** unless `"standard_out"` is added to the events list. Run
 * `26521692363` (commit `7bc73a9`) proved this empirically — `dumpJvmState`
 * ran (the catch fired) but its `println` output was swallowed by Gradle.
 *
 * JDK-only — no kotlinx-coroutines-debug agent involved. Adding the agent
 * would change scheduler timing enough to potentially mask the symptom; a
 * thread dump captures where each pool thread is parked, which is enough
 * to distinguish "selector blocked" / "channel send blocked" / "everyone
 * waiting on a deferred that never completes" without changing runtime
 * behaviour.
 */
internal fun captureJvmState(reason: String): String {
    val sb = StringBuilder(8192)
    val now = System.currentTimeMillis()
    val rt = Runtime.getRuntime()
    val heapMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    val maxMB = rt.maxMemory() / (1024 * 1024)
    val threadMx = ManagementFactory.getThreadMXBean()
    val allStacks = Thread.getAllStackTraces()

    sb.append("==== JVM STATE DUMP — ").append(reason).append(" ====\n")
    sb
        .append("ts=")
        .append(now)
        .append(" heapUsedMB=")
        .append(heapMB)
        .append(" heapMaxMB=")
        .append(maxMB)
        .append('\n')
    sb
        .append("threads: live=")
        .append(threadMx.threadCount)
        .append(" peak=")
        .append(threadMx.peakThreadCount)
        .append(" daemon=")
        .append(threadMx.daemonThreadCount)
        .append(" started=")
        .append(threadMx.totalStartedThreadCount)
        .append('\n')

    // Summary by name prefix so we can see if pool threads accumulated.
    val byPrefix =
        allStacks.keys
            .groupingBy { t -> t.name.replace(Regex("-?\\d+$"), "-N").take(60) }
            .eachCount()
    sb.append("thread-name-counts (prefix → N):\n")
    byPrefix.entries.sortedByDescending { it.value }.forEach { (k, v) ->
        sb
            .append("  ")
            .append(v)
            .append(" × ")
            .append(k)
            .append('\n')
    }

    // Full stacks of non-daemon threads + any thread whose name contains a
    // socket-quic-relevant token. JIT/GC daemons are suppressed for
    // readability.
    val relevant =
        allStacks.entries.filter { (t, _) ->
            !t.isDaemon ||
                t.name.contains("DefaultDispatcher", ignoreCase = true) ||
                t.name.contains("IO", ignoreCase = true) ||
                t.name.contains("kotlinx.coroutines", ignoreCase = true) ||
                t.name.contains("selector", ignoreCase = true) ||
                t.name.contains("nio", ignoreCase = true) ||
                t.name.contains("quic", ignoreCase = true)
        }

    sb.append("relevant thread stacks (").append(relevant.size).append("):\n")
    for ((thread, frames) in relevant.sortedBy { it.key.name }) {
        sb
            .append("--- ")
            .append(thread.name)
            .append(" (id=")
            // threadId(), not the id property: Thread.getId() is deprecated from JDK 19 and this
            // module's tests run on 21.
            .append(thread.threadId())
            .append(" state=")
            .append(thread.state)
            .append(" daemon=")
            .append(thread.isDaemon)
            .append(") ---\n")
        for (f in frames.take(25)) sb.append("    at ").append(f).append('\n')
        if (frames.size > 25) {
            sb.append("    ... ").append(frames.size - 25).append(" more frames\n")
        }
    }
    sb.append("==== END JVM STATE DUMP ====")
    return sb.toString()
}

/**
 * Wrap [block] with [kotlinx.coroutines.withTimeout]; on
 * [kotlinx.coroutines.TimeoutCancellationException] capture the JVM state
 * via [captureJvmState] and throw a wrapped [AssertionError] that embeds
 * the dump in its message. Gradle's failure formatter renders exception
 * messages verbatim, so the dump reaches the build log even when stdout
 * is suppressed by the testLogging events list.
 */
internal suspend fun <T> withDumpingTimeout(
    timeoutMillis: Long,
    reason: String,
    block: suspend () -> T,
): T {
    try {
        return kotlinx.coroutines.withTimeout(timeoutMillis) { block() }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        val dump = captureJvmState(reason)
        throw AssertionError("$reason — TIMEOUT after ${timeoutMillis}ms\n$dump", e)
    }
}
