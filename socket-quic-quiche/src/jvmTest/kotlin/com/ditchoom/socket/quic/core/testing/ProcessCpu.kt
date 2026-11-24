package com.ditchoom.socket.quic.core.testing

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** One process in a `ps` snapshot: its cumulative CPU time, and the `%cpu` and resident size `ps` reports. */
class ProcessRow(
    val pid: Int,
    val ppid: Int,
    val percent: Double,
    val rssKb: Long,
    val cpuMillis: Long,
    val command: String,
)

/** One read of `ps -A`: every process on the machine at one instant, or none where the host has no `ps`. */
sealed interface PsRead {
    data object NoPs : PsRead

    class Snapshot(
        val atNanos: Long,
        val rows: Map<Int, ProcessRow>,
    ) : PsRead
}

/** What the machine's processes did across a stall: unknown where `ps` cannot run (Windows), else their CPU deltas. */
sealed interface Processes {
    data object Unavailable : Processes {
        override fun toString() = "processes unknown (no ps on this host)"
    }

    /** The probe ran no snapshot before and after the stall: it was stopped too soon. */
    data object NotBracketed : Processes {
        override fun toString() = "processes not bracketed by a ps snapshot"
    }

    /** The busiest processes between the snapshots [intervalMillis] apart that bracket the stall. */
    class Seen(
        val intervalMillis: Long,
        val totalCpuMillis: Long,
        val busiest: List<Pair<ProcessRow, Long>>,
    ) : Processes {
        override fun toString() =
            "processes over the ${intervalMillis}ms around it used ${totalCpuMillis}ms CPU, busiest: " +
                busiest.joinToString { (row, millis) ->
                    "${row.command.substringAfterLast('/')} (pid ${row.pid}, parent ${row.ppid}) ${millis}ms"
                }
    }
}

/**
 * Reads `ps -A` every [periodMillis] on a daemon thread while armed, keeping the last [CAPACITY] snapshots, so a stall
 * found afterwards can be weighed against what every other process on the machine was doing. Cumulative CPU time is
 * the measure: `%cpu` is a decayed average on macOS and a lifetime average on Linux, so it says little about 20 ms.
 */
class ProcessCpuSampler private constructor(
    private val periodMillis: Long,
) {
    private val snapshots = ArrayDeque<PsRead.Snapshot>()
    private val stopping = AtomicBoolean()
    private val thread = Thread(::sample, "stall-probe-ps").apply { isDaemon = true }

    private fun sample() {
        while (!stopping.get()) {
            when (val read = snapshot()) {
                PsRead.NoPs -> return
                is PsRead.Snapshot -> {
                    snapshots.addLast(read)
                    if (snapshots.size > CAPACITY) snapshots.removeFirst()
                }
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(periodMillis))
        }
    }

    /** Stops sampling; the snapshots are safe to read once the thread has ended. */
    fun stop(): Recording {
        stopping.set(true)
        thread.join()
        // One more read, so a stall just before the stop is bracketed.
        when (val read = snapshot()) {
            PsRead.NoPs -> Unit
            is PsRead.Snapshot -> if (snapshots.isNotEmpty()) snapshots.addLast(read)
        }
        return Recording(snapshots.toList())
    }

    class Recording internal constructor(
        private val snapshots: List<PsRead.Snapshot>,
    ) {
        /** The processes' CPU between the last snapshot before [fromNanos] and the first after [toNanos]. */
        fun across(
            fromNanos: Long,
            toNanos: Long,
        ): Processes {
            if (snapshots.isEmpty()) return Processes.Unavailable
            val before = snapshots.lastOrNull { it.atNanos <= fromNanos } ?: return Processes.NotBracketed
            val after = snapshots.firstOrNull { it.atNanos >= toNanos } ?: return Processes.NotBracketed
            val used =
                after.rows.values.map { row ->
                    row to row.cpuMillis - (before.rows[row.pid]?.takeIf { it.command == row.command }?.cpuMillis ?: 0)
                }
            return Processes.Seen(
                intervalMillis = TimeUnit.NANOSECONDS.toMillis(after.atNanos - before.atNanos),
                totalCpuMillis = used.sumOf { it.second },
                busiest = used.sortedByDescending { it.second }.take(BUSIEST),
            )
        }
    }

    companion object {
        private const val CAPACITY = 256
        private const val BUSIEST = 8

        fun start(periodMillis: Long): ProcessCpuSampler = ProcessCpuSampler(periodMillis).also { it.thread.start() }

        /** The machine's processes now, busiest by `%cpu` first, as `ps` prints them: for a failure message. */
        fun topNow(rows: Int = 15): String =
            when (val read = snapshot()) {
                PsRead.NoPs -> "${Processes.Unavailable}"
                is PsRead.Snapshot ->
                    "pid ppid %cpu rssKB cpu-ms command\n" +
                        read.rows.values
                            .sortedByDescending { it.percent }
                            .take(rows)
                            .joinToString("\n") { "${it.pid} ${it.ppid} ${it.percent} ${it.rssKb} ${it.cpuMillis} ${it.command}" }
            }

        /** One `ps -A` read. */
        internal fun snapshot(): PsRead {
            val at = System.nanoTime()
            val output =
                runCatching {
                    val ps = ProcessBuilder("ps", "-A", "-o", "pid=,ppid=,%cpu=,rss=,time=,comm=").redirectErrorStream(true).start()
                    val text = ps.inputStream.bufferedReader().readText()
                    if (ps.waitFor(5, TimeUnit.SECONDS) && ps.exitValue() == 0) text else null
                }.getOrNull() ?: return PsRead.NoPs
            val rows =
                output.lineSequence().mapNotNull { line ->
                    val fields = line.trim().split(WHITESPACE, limit = 6)
                    if (fields.size < 6) return@mapNotNull null
                    ProcessRow(
                        pid = fields[0].toIntOrNull() ?: return@mapNotNull null,
                        ppid = fields[1].toIntOrNull() ?: return@mapNotNull null,
                        percent = fields[2].toDoubleOrNull() ?: return@mapNotNull null,
                        rssKb = fields[3].toLongOrNull() ?: return@mapNotNull null,
                        cpuMillis = cpuMillisOf(fields[4]) ?: return@mapNotNull null,
                        command = fields[5],
                    )
                }
            return PsRead.Snapshot(at, rows.associateBy { it.pid })
        }

        /** `ps` time: macOS `MMM:SS.ss`, Linux `[DD-]HH:MM:SS`. */
        internal fun cpuMillisOf(time: String): Long? {
            val days = time.substringBefore('-', "0").toLongOrNull() ?: return null
            val parts = time.substringAfter('-').split(':')
            val seconds = parts.last().toDoubleOrNull() ?: return null
            val (hours, minutes) =
                when (parts.size) {
                    2 -> 0L to (parts[0].toLongOrNull() ?: return null)
                    3 -> (parts[0].toLongOrNull() ?: return null) to (parts[1].toLongOrNull() ?: return null)
                    else -> return null
                }
            return ((days * 24 + hours) * 60 + minutes) * 60_000 + (seconds * 1_000).toLong()
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
