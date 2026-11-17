package com.ditchoom.socket.quic.core.testing

import java.io.File

/** One thread's scheduling as the kernel counted it: time on a CPU, time runnable without one, sleeps and preemptions. */
internal class Sched(
    val name: String,
    val nice: Int,
    val runNanos: Long,
    val waitNanos: Long,
    val sleeps: Long,
    val preemptions: Long,
)

/**
 * How the kernel scheduled this process's loop threads (named `quic-…`), from `/proc/self/task`: whether a slow pass
 * was the loop working, the loop runnable while other threads held the CPUs, or the loop asleep on something. Linux and
 * Android only; elsewhere it says so. Read at a test's start and end, so a failure names where the time went.
 */
internal object LoopScheduling {
    private val tasks = File("/proc/self/task")

    fun snapshot(): Map<String, Sched> =
        if (!tasks.isDirectory) {
            emptyMap()
        } else {
            tasks
                .listFiles()
                .orEmpty()
                .mapNotNull { read(it) }
                .associateBy { it.name }
        }

    /** The loop threads' scheduling since [before], one line each; threads that started since count from zero. */
    fun since(before: Map<String, Sched>): String {
        if (!tasks.isDirectory) return "no /proc/self/task on this platform"
        return snapshot()
            .values
            .sortedBy { it.name }
            .joinToString("; ") { now ->
                val was = before[now.name]
                "${now.name} (nice ${now.nice}): ran ${(now.runNanos - (was?.runNanos ?: 0)) / MILLI} ms, " +
                    "runnable without a CPU ${(now.waitNanos - (was?.waitNanos ?: 0)) / MILLI} ms, " +
                    "${now.sleeps - (was?.sleeps ?: 0)} sleeps, ${now.preemptions - (was?.preemptions ?: 0)} preemptions"
            }.ifEmpty { "no loop thread" }
    }

    private fun read(task: File): Sched? =
        runCatching {
            val comm = File(task, "comm").readText().trim()
            if (!comm.startsWith("quic")) return null
            val sched = File(task, "schedstat").readText().trim().split(" ")
            val status = File(task, "status").readLines()
            // stat: "pid (comm) state ..." — the comm may hold spaces, so fields count from the closing parenthesis.
            val stat =
                File(task, "stat")
                    .readText()
                    .substringAfterLast(')')
                    .trim()
                    .split(" ")
            Sched(
                name = "$comm#${task.name}",
                nice = stat[NICE_AFTER_COMM].toInt(),
                runNanos = sched[0].toLong(),
                waitNanos = sched[1].toLong(),
                sleeps = countOf(status, "voluntary_ctxt_switches"),
                preemptions = countOf(status, "nonvoluntary_ctxt_switches"),
            )
        }.getOrNull()

    private fun countOf(
        status: List<String>,
        key: String,
    ): Long =
        status
            .first { it.startsWith("$key:") }
            .substringAfter(':')
            .trim()
            .toLong()

    private const val MILLI = 1_000_000L

    /** `nice` is field 19 of `stat`; counted from the state, the first field after the comm, it is index 16. */
    private const val NICE_AFTER_COMM = 16
}
