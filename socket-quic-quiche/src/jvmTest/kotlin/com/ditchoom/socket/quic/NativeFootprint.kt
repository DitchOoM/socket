package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.nativeMemoryAccess
import java.io.File
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory

/**
 * How this test JVM can *see* native memory — the memory a QUIC read buffer actually occupies, which
 * no managed-heap counter tracks.
 *
 * The **resident set** is the primary instrument, because it is the one that does not depend on any
 * particular allocator's bookkeeping: it counts pages, so it sees every tier, and it is the number the
 * device walk behind #538 died on. `/proc/self/status`'s `VmRSS` where procfs exists, `ps -o rss=`
 * otherwise.
 *
 * [java.lang.management.BufferPoolMXBean]'s `direct` pool is carried alongside as a second, sharper
 * reading, and its coverage was **measured rather than assumed** — the assumption would have been
 * wrong. On this build's JDK 21 the QUIC read buffer is an `Arena.ofShared()` segment whose
 * `MemorySegment.asByteBuffer()` view *is* counted by that pool: a leaking soak moved it by exactly
 * 4000 buffers / 262 144 000 bytes for 2000 reads on two in-process peers, while a healthy soak left
 * it flat at 34. It is also the natural instrument for the JDK 17 / Android tier's
 * `ByteBuffer.allocateDirect`. It stays the *second* assertion rather than the first because it is a
 * JVM-implementation detail of how one factory's memory reaches Java, and a future buffer release that
 * allocated differently could quietly stop feeding it — the resident set cannot.
 */
internal sealed interface NativeFootprintMeter {
    /** A human-readable name for the failure message, so a red run says how it was measured. */
    val description: String

    /** Resident set size in KiB. */
    fun residentKb(): Long

    /**
     * Linux: `/proc/self/status`'s `VmRSS`. Preferred where it exists — it is the same number `ps`
     * would report, read without forking a process, so the instrument does not perturb what it measures.
     */
    data object ProcSelfStatus : NativeFootprintMeter {
        override val description = "/proc/self/status VmRSS"

        override fun residentKb(): Long {
            val line =
                File("/proc/self/status")
                    .readLines()
                    .first { it.startsWith("VmRSS:") }
            return line
                .substringAfter(':')
                .trim()
                .removeSuffix(" kB")
                .trim()
                .toLong()
        }
    }

    /** macOS (and any other POSIX without procfs): `ps -o rss= -p <pid>`, in KiB. */
    data object PsRss : NativeFootprintMeter {
        override val description = "ps -o rss= -p <pid>"

        override fun residentKb(): Long {
            val pid = ProcessHandle.current().pid()
            val process = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString()).start()
            val out =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            check(process.waitFor() == 0 && out.isNotEmpty()) { "ps gave no rss for pid $pid" }
            return out.toLong()
        }
    }

    companion object {
        /** The meter for this host. */
        fun forThisProcess(): NativeFootprintMeter = if (File("/proc/self/status").exists()) ProcSelfStatus else PsRss
    }
}

/**
 * Which `BufferFactory.Default` this JVM resolved — i.e. which half of buffer's multi-release JAR is on
 * the runtime classpath. Named rather than inferred from the JDK version, because the selection is the
 * classloader's and a wrong guess would silently mis-attribute a measurement (#386 is the same trap on
 * the other side of the jar).
 */
internal sealed interface DefaultBufferTier {
    val bufferClass: String

    /** JDK 21+: `FfmAutoBuffer` over `Arena.ofAuto()`. `freeNativeMemory()` is a no-op by design. */
    data class CollectorOwnedFfmArena(
        override val bufferClass: String,
    ) : DefaultBufferTier

    /** JDK 17 / Android: a direct `ByteBuffer` released by its `Cleaner`. */
    data class CollectorOwnedDirectByteBuffer(
        override val bufferClass: String,
    ) : DefaultBufferTier

    /** No native memory behind it at all — not a tier the JVM is expected to resolve. */
    data class ManagedHeap(
        override val bufferClass: String,
    ) : DefaultBufferTier

    companion object {
        fun resolve(): DefaultBufferTier {
            val probe = BufferFactory.Default.allocate(1)
            val name = probe::class.java.name
            val native = probe.nativeMemoryAccess != null
            probe.freeIfNeeded()
            return when {
                !native -> ManagedHeap(name)
                name.contains("Ffm") -> CollectorOwnedFfmArena(name)
                else -> CollectorOwnedDirectByteBuffer(name)
            }
        }
    }
}

/** The JVM's `direct` buffer pool — see the header for what it was measured to cover here. */
internal fun directBufferPool(): BufferPoolMXBean =
    ManagementFactory
        .getPlatformMXBeans(BufferPoolMXBean::class.java)
        .first { it.name == "direct" }

/**
 * One sample of everything worth reporting when a footprint assertion fails.
 *
 * [nativeKb] is the number the assertion is about: the resident set **minus the heap the JVM has
 * committed**. The subtraction is not cosmetic. A soak loop produces managed garbage as well as native
 * allocations — coroutine frames, one command object per read — and G1 answers that by *expanding the
 * heap*, touching pages that land in RSS and stay there across a `System.gc()` (a collection returns
 * objects, not necessarily address space). Measured on the fixed path: ~28 MB of resident growth over
 * 3000 reads, essentially all of it heap. Left in, that noise scales with the loop count, so it would
 * either need a bound loose enough for the defect to hide under, or it would flake red on a runner
 * whose collector sizes differently. Subtracting the committed heap leaves the part of the footprint
 * that a leaked read buffer actually moves.
 */
internal data class FootprintSample(
    val label: String,
    val residentKb: Long,
    val heapCommittedKb: Long,
    val heapUsedKb: Long,
    val directPoolCount: Long,
    val directPoolBytes: Long,
) {
    /** Resident set outside the committed Java heap — where a leaked native buffer shows up. */
    val nativeKb: Long get() = residentKb - heapCommittedKb

    override fun toString(): String =
        "$label: native=${nativeKb}kB (rss=${residentKb}kB - heapCommitted=${heapCommittedKb}kB, " +
            "heapUsed=${heapUsedKb}kB) directPool(count=$directPoolCount, bytes=$directPoolBytes)"
}

internal fun sampleFootprint(
    label: String,
    meter: NativeFootprintMeter,
): FootprintSample {
    val pool = directBufferPool()
    val runtime = Runtime.getRuntime()
    val committed = runtime.totalMemory()
    return FootprintSample(
        label = label,
        residentKb = meter.residentKb(),
        heapCommittedKb = committed / 1024,
        heapUsedKb = (committed - runtime.freeMemory()) / 1024,
        directPoolCount = pool.count,
        directPoolBytes = pool.memoryUsed,
    )
}

/**
 * Give the collector every chance to run and to hand memory back, so a footprint measured afterwards
 * cannot be dismissed as "the GC just hadn't got round to it".
 *
 * This is deliberately more than a courtesy: on the JDK 21+ tier `BufferFactory.Default`'s memory really
 * is collector-owned, so if a leak were merely un-collected garbage this loop would erase it. It does
 * not, and that is the point — the QUIC read path allocates from `BufferFactory.network()`
 * (`deterministic()`, an `Arena.ofShared()` on this tier), whose memory is released by an explicit
 * `freeNativeMemory()` and by nothing else. A buffer the caller never releases is unreachable *and*
 * still mapped, forever.
 */
internal fun settleAndCollect(rounds: Int = 3) {
    repeat(rounds) {
        System.gc()
        System.runFinalization()
        Thread.sleep(SETTLE_MILLIS)
    }
}

private const val SETTLE_MILLIS = 400L
