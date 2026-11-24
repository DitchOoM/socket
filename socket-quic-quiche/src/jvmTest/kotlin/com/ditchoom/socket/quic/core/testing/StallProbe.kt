package com.ditchoom.socket.quic.core.testing

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.io.path.name
import kotlin.io.path.readLines

/**
 * Watches one thread while armed, then explains a stall of it: a daemon samples the thread's CPU time and state about
 * every millisecond, with the depth of the queue that feeds it and the process's CPU time, and every [RUNNABLE_EVERY]
 * samples counts the RUNNABLE threads. [Recording.explain] adds the pauses this worker's safepoint log (the root build's
 * `-Xlog:safepoint`) shows overlapping the stall. Every read is safepoint-free, so the probe cannot cause what it finds.
 */
class StallProbe private constructor(
    private val target: Thread,
    private val queue: Queue,
) {
    /** What feeds the watched thread: nothing the probe can see, or a queue whose depth it reads. */
    sealed interface Queue {
        data object Unseen : Queue

        class Depth(
            val read: () -> Int,
        ) : Queue
    }

    private val threads = ManagementFactory.getThreadMXBean()
    private val process = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    private val uptimeAtStartMillis = ManagementFactory.getRuntimeMXBean().uptime
    private val nanosAtStart = System.nanoTime()
    private val at = LongArray(CAPACITY)
    private val cpu = LongArray(CAPACITY)
    private val processCpu = LongArray(CAPACITY)
    private val state = IntArray(CAPACITY)
    private val depth = IntArray(CAPACITY)

    /** RUNNABLE threads at a sample, or -1 between the samples that count them. */
    private val runnable = IntArray(CAPACITY)
    private var count = 0
    private val stopping = AtomicBoolean()
    private val sampler = Thread(::sample, "stall-probe").apply { isDaemon = true }

    private fun sample() {
        // Thread.threadId() is JDK 19+, and a CI lane runs the tests on a JDK 17 launcher.
        @Suppress("DEPRECATION")
        val targetId = target.id
        while (!stopping.get() && count < CAPACITY) {
            val i = count
            at[i] = System.nanoTime()
            cpu[i] = threads.getThreadCpuTime(targetId)
            processCpu[i] = process.processCpuTime
            state[i] = target.state.ordinal
            depth[i] =
                when (queue) {
                    Queue.Unseen -> 0
                    is Queue.Depth -> queue.read()
                }
            // A thread-info read without stack frames takes no safepoint; one with frames would.
            runnable[i] =
                if (i % RUNNABLE_EVERY == 0) {
                    threads.getThreadInfo(threads.allThreadIds, 0).count { it?.threadState == Thread.State.RUNNABLE }
                } else {
                    -1
                }
            count = i + 1
            LockSupport.parkNanos(PERIOD_NANOS)
        }
    }

    /** Stops sampling; what was sampled is safe to read once the sampler has ended. */
    fun stop(): Recording {
        stopping.set(true)
        sampler.join()
        return Recording()
    }

    /** What the probe sampled, from its start to [stop]. */
    inner class Recording internal constructor() {
        /**
         * The stall from [fromNanos] to [toNanos] (`System.nanoTime` instants): the samples inside it, and the
         * safepoints that overlap it. CPU is counted between the first and last samples inside, so the work either
         * side of the stall stays out; with under two inside (the probe was frozen too), between the two around it.
         */
        fun explain(
            fromNanos: Long,
            toNanos: Long,
        ): Stall {
            check(count > 1) { "the probe took $count samples" }
            val first = (0 until count).lastOrNull { at[it] <= fromNanos } ?: 0
            val last = (first until count).firstOrNull { at[it] >= toNanos } ?: (count - 1)
            val inside = (first..last).filter { at[it] in fromNanos..toNanos }
            val span = if (inside.size >= 2) inside.first() to inside.last() else first to last
            val seen = inside.ifEmpty { listOf(first, last) }
            val states = seen.groupingBy { Thread.State.entries[state[it]] }.eachCount()
            val counted = (first..last).map { runnable[it] }.filter { it >= 0 }
            return Stall(
                windowMicros = (toNanos - fromNanos) / 1_000,
                sampledMicros = (at[span.second] - at[span.first]) / 1_000,
                samples = inside.size,
                targetCpuMicros = (cpu[span.second] - cpu[span.first]) / 1_000,
                states = states,
                dominant = states.maxBy { it.value }.key,
                maxQueueDepth = seen.maxOf { depth[it] },
                maxRunnable = counted.maxOrNull() ?: runnableNear(first),
                cores = Runtime.getRuntime().availableProcessors(),
                processCpuMicros = (processCpu[span.second] - processCpu[span.first]) / 1_000,
                samplerLongestMicros = ((first + 1)..last).maxOfOrNull { at[it] - at[it - 1] }?.div(1_000) ?: 0,
                safepoints = safepointsOverlapping(uptimeMicros(fromNanos), uptimeMicros(toNanos)),
            )
        }

        /** The last count of RUNNABLE threads at or before sample [index]. */
        private fun runnableNear(index: Int): Int = (index downTo 0).map { runnable[it] }.first { it >= 0 }

        private fun uptimeMicros(nanos: Long) = uptimeAtStartMillis * 1_000 + (nanos - nanosAtStart) / 1_000
    }

    companion object {
        private const val CAPACITY = 32_768
        private const val PERIOD_NANOS = 1_000_000L
        private const val RUNNABLE_EVERY = 10

        /** Starts watching [target], fed by [queue]. */
        fun start(
            target: Thread,
            queue: Queue,
        ): StallProbe = StallProbe(target, queue).also { it.sampler.start() }

        /** `[1.234s][info][safepoint     ] Safepoint "ThreadDump", ... Total: 336917 ns`, logged as the pause ends. */
        private val SAFEPOINT = Regex("""^\[(\d+)\.(\d+)s]\[info\s*]\[safepoint\s*] Safepoint "([^"]+)",.* Total: (\d+) ns""")
    }

    /** Safepoint pauses, by operation, that overlap the window from [fromMicros] to [toMicros] of JVM uptime. */
    private fun safepointsOverlapping(
        fromMicros: Long,
        toMicros: Long,
    ): Safepoints =
        when (val log = SafepointLog.ofThisWorker()) {
            SafepointLog.NotLogged -> Safepoints.NotLogged
            is SafepointLog.Logged -> {
                val overlap = mutableMapOf<String, Long>()
                log.lines().forEach { line ->
                    val match = SAFEPOINT.find(line) ?: return@forEach
                    val (seconds, fraction, operation, total) = match.destructured
                    val end = seconds.toLong() * 1_000_000 + fraction.padEnd(6, '0').take(6).toLong()
                    val start = end - total.toLong() / 1_000
                    val covered = minOf(end, toMicros) - maxOf(start, fromMicros)
                    if (covered > 0) overlap.merge(operation, covered, Long::plus)
                }
                Safepoints.Logged(overlap)
            }
        }
}

/** This worker's safepoint log, found in the `-Xlog:safepoint...:file=...` argument it was started with. */
private sealed interface SafepointLog {
    data object NotLogged : SafepointLog

    class Logged(
        val file: Path,
    ) : SafepointLog {
        /** The current file and the ones it rotated out. */
        fun lines(): List<String> {
            val rotated =
                Files.list(file.parent).use { files ->
                    files.filter { it.name.startsWith("${file.name}.") }.sorted().toList()
                }
            return (rotated + listOf(file)).filter(Files::exists).flatMap { it.readLines() }
        }
    }

    companion object {
        private val ARGUMENT = Regex("""^-Xlog:safepoint[^:]*:file=([^:]+)""")

        fun ofThisWorker(): SafepointLog {
            val file =
                ManagementFactory.getRuntimeMXBean().inputArguments.firstNotNullOfOrNull { ARGUMENT.find(it)?.groupValues?.get(1) }
                    ?: return NotLogged
            val pid = ProcessHandle.current().pid().toString()
            return Logged(Paths.get(System.getProperty("user.dir")).resolve(file.replace("%p", pid)))
        }
    }
}

/** The safepoint pauses overlapping a stall: unknown when this worker logs none, else the overlap by operation. */
sealed interface Safepoints {
    data object NotLogged : Safepoints {
        override fun toString() = "safepoints not logged (the root build's -PjvmLogs is false)"
    }

    class Logged(
        val overlapMicrosByOperation: Map<String, Long>,
    ) : Safepoints {
        val overlapMicros = overlapMicrosByOperation.values.sum()

        override fun toString() = "safepoints ${overlapMicros / 1_000.0} ms overlapping $overlapMicrosByOperation"
    }
}

/**
 * What the watched thread and its process did across one stall. [samplerLongestMicros] is the probe's own longest wait
 * between samples: about [windowMicros] when every thread, the probe's too, was frozen.
 */
class Stall(
    val windowMicros: Long,
    val sampledMicros: Long,
    val samples: Int,
    val targetCpuMicros: Long,
    val states: Map<Thread.State, Int>,
    val dominant: Thread.State,
    val maxQueueDepth: Int,
    val maxRunnable: Int,
    val cores: Int,
    val processCpuMicros: Long,
    val samplerLongestMicros: Long,
    val safepoints: Safepoints,
) {
    override fun toString() =
        "stall of ${windowMicros / 1_000.0} ms ($samples samples over ${sampledMicros / 1_000.0} ms): " +
            "reader CPU ${targetCpuMicros / 1_000.0} ms, states $states (mostly $dominant), queue depth up to $maxQueueDepth; " +
            "RUNNABLE threads up to $maxRunnable on $cores cores, process CPU ${processCpuMicros / 1_000.0} ms; " +
            "probe's longest wait ${samplerLongestMicros / 1_000.0} ms; $safepoints"
}
