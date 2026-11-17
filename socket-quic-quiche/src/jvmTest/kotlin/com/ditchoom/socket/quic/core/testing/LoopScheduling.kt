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
    /** Clock ticks in user mode and in the kernel, from `stat`: whether the time went to our code or to system calls. */
    val userTicks: Long,
    val kernelTicks: Long,
)

/**
 * How the kernel scheduled this process's loop threads (named `quic-…`), from `/proc/self/task`: whether a slow pass
 * was the loop working, the loop runnable while other threads held the CPUs, or the loop asleep on something. Linux and
 * Android only; elsewhere it says so. Read at a test's start and end, so a failure names where the time went.
 */
internal object LoopScheduling {
    private val tasks = File("/proc/self/task")

    fun snapshot(): Map<String, Sched> = snapshotOf { it.startsWith("quic") }

    /** Every thread of the process: the loops, the runtime's own (GC, JIT, finalizers) and the dispatchers'. */
    fun snapshotAll(): Map<String, Sched> = snapshotOf { true }

    private fun snapshotOf(wanted: (String) -> Boolean): Map<String, Sched> =
        if (!tasks.isDirectory) {
            emptyMap()
        } else {
            tasks
                .listFiles()
                .orEmpty()
                .mapNotNull { read(it, wanted) }
                .associateBy { it.name }
        }

    /**
     * The process's CPU since [before] (a [snapshotAll]), per [connections]: the total, its user and kernel split, and
     * the threads grouped by name without their numbers, costliest first. A passing run prints it, so API levels compare.
     */
    fun cpuPerConnection(
        before: Map<String, Sched>,
        connections: Int,
    ): String {
        if (!tasks.isDirectory) return "no /proc/self/task on this platform"
        val spent =
            snapshotAll().values.map { now ->
                val was = before[now.name]
                Spent(
                    group = now.name.substringBefore('#').trimEnd { it.isDigit() || it == '-' || it == ' ' },
                    runNanos = now.runNanos - (was?.runNanos ?: 0),
                    waitNanos = now.waitNanos - (was?.waitNanos ?: 0),
                    userTicks = now.userTicks - (was?.userTicks ?: 0),
                    kernelTicks = now.kernelTicks - (was?.kernelTicks ?: 0),
                )
            }
        val total = spent.sumOf { it.runNanos }
        val groups =
            spent
                .groupBy { it.group }
                .map { (group, all) -> group to all }
                .sortedByDescending { (_, all) -> all.sumOf { it.runNanos } }
                .take(TOP_GROUPS)
                .joinToString(", ") { (group, all) ->
                    "$group x${all.size} ${all.sumOf { it.runNanos } / MILLI} ms " +
                        "(user ${all.sumOf { it.userTicks }} kernel ${all.sumOf { it.kernelTicks }} ticks, " +
                        "runnable ${all.sumOf { it.waitNanos } / MILLI} ms)"
                }
        return "ran ${total / MILLI} ms = ${total / connections / MICRO} us per connection " +
            "(user ${spent.sumOf { it.userTicks }} kernel ${spent.sumOf { it.kernelTicks }} ticks, " +
            "runnable without a CPU ${spent.sumOf { it.waitNanos } / MILLI} ms); $groups"
    }

    /**
     * ART's collector counters (`android.os.Debug.getRuntimeStats`, reached by reflection so the JVM build compiles):
     * collections and their time, so a report says how much of the CPU the collector took. Empty off Android.
     */
    fun gcStats(): Map<String, Long> =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val stats =
                Class
                    .forName("android.os.Debug")
                    .getMethod("getRuntimeStats")
                    .invoke(null) as Map<String, String>
            stats.mapNotNull { (key, value) -> value.toLongOrNull()?.let { key.removePrefix("art.gc.") to it } }.toMap()
        }.getOrElse { emptyMap() }

    /** The collector's counters since [before] (a [gcStats]), and the Java heap in use now. */
    fun gcSince(before: Map<String, Long>): String {
        val runtime = Runtime.getRuntime()
        val heap = "heap ${(runtime.totalMemory() - runtime.freeMemory()) shr 20} of ${runtime.maxMemory() shr 20} MiB"
        val now = gcStats()
        if (now.isEmpty()) return heap
        return "$heap; gc " +
            now.keys.sorted().joinToString(", ") { key -> "$key ${now.getValue(key) - (before[key] ?: 0L)}" }
    }

    private class Spent(
        val group: String,
        val runNanos: Long,
        val waitNanos: Long,
        val userTicks: Long,
        val kernelTicks: Long,
    )

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

    private fun read(
        task: File,
        wanted: (String) -> Boolean,
    ): Sched? =
        runCatching {
            val comm = File(task, "comm").readText().trim()
            if (!wanted(comm)) return null
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
                userTicks = stat[UTIME_AFTER_COMM].toLong(),
                kernelTicks = stat[STIME_AFTER_COMM].toLong(),
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
    private const val MICRO = 1_000L
    private const val TOP_GROUPS = 8

    /** `utime` and `stime` are fields 14 and 15 of `stat`: indices 11 and 12 counted from the state. */
    private const val UTIME_AFTER_COMM = 11
    private const val STIME_AFTER_COMM = 12

    /** `nice` is field 19 of `stat`; counted from the state, the first field after the comm, it is index 16. */
    private const val NICE_AFTER_COMM = 16
}
