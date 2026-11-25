package com.ditchoom.socket.quic.core.testing

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.io.path.name
import kotlin.io.path.readLines

/**
 * Watches one thread while armed, then explains a stall of it: a daemon samples the thread's CPU time and state about
 * every millisecond, with the depth of the queue that feeds it and the process's CPU time, and every [RUNNABLE_EVERY]
 * samples counts the RUNNABLE threads. [Recording.explain] adds the pauses this worker's safepoint log (the root build's
 * `-Xlog:safepoint`) shows overlapping the stall, and what every process on the machine used around it ([ProcessCpuSampler]).
 * Every read is safepoint-free, so the probe cannot cause what it finds.
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
    private var stoppedAt = 0L
    private val stopping = AtomicBoolean()
    private val sampling = CountDownLatch(1)
    private val sampler = Thread(::sample, "stall-probe").apply { isDaemon = true }
    private val ps = ProcessCpuSampler.start(PS_PERIOD_MILLIS)

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
            if (i == 0) sampling.countDown()
            LockSupport.parkNanos(PERIOD_NANOS)
        }
    }

    /** Stops sampling; what was sampled is safe to read once the sampler has ended. */
    fun stop(): Recording {
        stopping.set(true)
        sampler.join()
        stoppedAt = System.nanoTime()
        return Recording(ps.stop())
    }

    /** What the probe sampled, from its start to [stop]. */
    inner class Recording internal constructor(
        private val processes: ProcessCpuSampler.Recording,
    ) {
        /**
         * The stall from [fromNanos] to [toNanos] (`System.nanoTime` instants): the samples inside it, and the
         * safepoints that overlap it. The thread's state and CPU need [MIN_SAMPLES] inside, counted between the first
         * and last of them so the work either side of the stall stays out; with fewer, the safepoints and processes are
         * still reported, as [Explanation.TooFewSamples].
         */
        fun explain(
            fromNanos: Long,
            toNanos: Long,
        ): Explanation {
            val safepoints = safepointsOverlapping(uptimeMicros(fromNanos), uptimeMicros(toNanos))
            val around = processes.across(fromNanos, toNanos)
            val inside = (0 until count).filter { at[it] in fromNanos..toNanos }
            if (inside.size < MIN_SAMPLES) {
                // The probe's own longest silence over its whole run, from its start to its stop.
                val instants = listOf(nanosAtStart) + (0 until count).map { at[it] } + listOf(stoppedAt)
                return Explanation.TooFewSamples(
                    windowMicros = (toNanos - fromNanos) / 1_000,
                    samples = inside.size,
                    samplesInAll = count,
                    probeLongestWaitMicros = instants.zipWithNext { a, b -> b - a }.max() / 1_000,
                    safepoints = safepoints,
                    processes = around,
                )
            }
            val first = inside.first()
            val last = inside.last()
            val states = inside.groupingBy { Thread.State.entries[state[it]] }.eachCount()
            val counted = (first..last).map { runnable[it] }.filter { it >= 0 }
            return Explanation.Attributed(
                Stall(
                    windowMicros = (toNanos - fromNanos) / 1_000,
                    sampledMicros = (at[last] - at[first]) / 1_000,
                    samples = inside.size,
                    targetCpuMicros = (cpu[last] - cpu[first]) / 1_000,
                    states = states,
                    dominant = states.maxBy { it.value }.key,
                    maxQueueDepth = inside.maxOf { depth[it] },
                    maxRunnable = counted.maxOrNull() ?: runnableNear(first),
                    cores = Runtime.getRuntime().availableProcessors(),
                    processCpuMicros = (processCpu[last] - processCpu[first]) / 1_000,
                    samplerLongestMicros = ((first + 1)..last).maxOfOrNull { at[it] - at[it - 1] }?.div(1_000) ?: 0,
                    safepoints = safepoints,
                    processes = around,
                ),
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

        /** Samples inside a stall that its thread's state and CPU need: two, for a CPU difference between them. */
        const val MIN_SAMPLES = 2

        /** `ps` costs a fork on a machine that may be short of CPU: twice a second is enough to name the busiest. */
        private const val PS_PERIOD_MILLIS = 500L

        /**
         * Starts watching [target], fed by [queue], and returns once the first sample is taken (or after ten seconds of
         * a sampler that never ran), so a window the caller opens next is one the probe is already sampling.
         */
        fun start(
            target: Thread,
            queue: Queue,
        ): StallProbe =
            StallProbe(target, queue).also {
                it.sampler.start()
                it.sampling.await(10, TimeUnit.SECONDS)
            }

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

/** A stall's explanation: the watched thread's part in it, or too few samples inside it to say. */
sealed interface Explanation {
    val safepoints: Safepoints
    val processes: Processes

    class Attributed(
        val stall: Stall,
    ) : Explanation {
        override val safepoints get() = stall.safepoints
        override val processes get() = stall.processes

        override fun toString() = "$stall"
    }

    /**
     * The probe took [samples] inside the window ([StallProbe.MIN_SAMPLES] needed), [samplesInAll] over its run, and was
     * itself unscheduled for up to [probeLongestWaitMicros]: the runner did not run it often enough to watch the stall.
     */
    class TooFewSamples(
        val windowMicros: Long,
        val samples: Int,
        val samplesInAll: Int,
        val probeLongestWaitMicros: Long,
        override val safepoints: Safepoints,
        override val processes: Processes,
    ) : Explanation {
        override fun toString() =
            "too few samples to attribute a stall of ${windowMicros / 1_000.0} ms: $samples inside it " +
                "(${StallProbe.MIN_SAMPLES} needed), $samplesInAll in all, the probe unscheduled for up to " +
                "${probeLongestWaitMicros / 1_000.0} ms; $safepoints; $processes"
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
    val processes: Processes,
) {
    override fun toString() =
        "stall of ${windowMicros / 1_000.0} ms ($samples samples over ${sampledMicros / 1_000.0} ms): " +
            "reader CPU ${targetCpuMicros / 1_000.0} ms, states $states (mostly $dominant), queue depth up to $maxQueueDepth; " +
            "RUNNABLE threads up to $maxRunnable on $cores cores, process CPU ${processCpuMicros / 1_000.0} ms; " +
            "probe's longest wait ${samplerLongestMicros / 1_000.0} ms; $safepoints; $processes"
}
