package com.ditchoom.socket.quic

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * **The process's native footprint stays flat across thousands of real QUIC stream reads** (#538).
 *
 * This is the test the 2026-08-30 device walk needed and did not have. The probe read 45 382 echoes
 * over 2 h 36 m, dropped every read buffer — which the KDoc of the day said was harmless under the
 * default factory — and died at **VmSize 20.8 GB** with `std::bad_alloc` raised inside the stack
 * unwinder, so the process aborted instead of reporting anything. Nothing in the suite could have
 * caught it: every existing leak assertion counts *allocations* through an injected factory
 * (`TrackingBufferFactory`, `QuicReadPoolingTests`) or *receipts* through a harness funnel
 * (`EchoOwnershipLedger`), and both are blind to a consumer that simply never releases what it was
 * handed. This one measures the only thing the walk could see: pages the process is holding.
 *
 * ## What is measured, and with what
 *
 * Two instruments, in that order of authority:
 *
 * 1. **The resident set** (`/proc/self/status` VmRSS, or `ps -o rss=`), minus the heap the JVM has
 *    committed — see [FootprintSample] for why that subtraction is load-bearing. It counts pages
 *    rather than allocator bookkeeping, so it sees every tier, and it is the number the device walk
 *    actually died on.
 * 2. **The count of live direct buffers** ([java.lang.management.BufferPoolMXBean]). Sharper and
 *    essentially noise-free — one live buffer per unreleased read — but it reads a JVM implementation
 *    detail of how one factory's memory reaches Java, so it corroborates rather than decides. Its
 *    coverage of this tier was measured rather than assumed; the reasoning is in [NativeFootprintMeter].
 *
 * [DefaultBufferTier] names which half of buffer's multi-release JAR this JVM resolved, so a
 * measurement can never be silently mis-attributed to the wrong tier.
 *
 * Note *which* factory is on trial. The QUIC read path does not allocate from
 * `BufferFactory.Default` at all: `quicBufferFactory()` resolves to `BufferFactory.network()` =
 * `deterministic()`, an `Arena.ofShared()` on this tier, whose memory is released by an explicit
 * `freeNativeMemory()` **and by nothing else**. A read buffer the caller drops is unreachable and
 * still mapped, permanently — which is why [settleAndCollect] runs a real `System.gc()` between the
 * measured phase and the assertion. That settle is not a courtesy to the collector; it is what makes
 * the number un-dismissable, and it demonstrably does not rescue a leak. Measured twice: this test's
 * own mutation run gave back **1.4 MB of the 277 MB** it had leaked, and a standalone probe that
 * dropped 10 000 `Arena.ofAuto` 64 KB segments — the *collector-owned* tier, the one the settle could
 * in principle rescue — raised RSS by 547 MB and got 45 MB of it back.
 *
 * ## Why it can fail
 *
 * A memory test that cannot be shown to go red is decoration, so this one is calibrated from
 * measurement rather than guessed:
 *
 * - **Fixed**, four isolated runs at [MEASURED_READS] reads: **+21.6, +16.4, +22.6, +25.5 MB**
 *   (1.7-2.6 kB/read) and **+0 live direct buffers** every time. That growth is sublinear in
 *   the read count — 22 MB by 3000 reads, still only 32 MB by 12 000 — i.e. a saturating start-up
 *   cost (JIT code cache, metaspace, allocator arenas), not a per-read one. Run inside the full
 *   389-test `jvmTest` worker, after 380 other tests have warmed everything, it goes **negative**
 *   (−15.5 MB): the settle hands back more than the loop takes.
 * - **Leaking**, with the scoped read's `finally` deleted so client *and* server drop every buffer:
 *   **+277 MB over 2000 reads** (141.9 kB/read — two 64 KB buffers per round trip) and **+4000 live
 *   direct buffers**, exactly two per read. Red on the first run, on both assertions.
 *
 * [MAX_GROWTH_KB] therefore sits ~5x above the worst observed noise and ~10x below what the defect
 * produces at [MEASURED_READS]. The per-read figure in the failure message is what makes a red run
 * diagnostic: ~64 kB/read is *this* bug; a few hundred bytes per read is something else.
 *
 * ## Cost
 *
 * [MEASURED_READS] round trips over loopback, in-process, both peers in this JVM. Sized to stay well
 * inside a normal `jvmTest` lane; `QUIC_MEMORY_SOAK_READS` scales it up for an investigation run
 * (the [SimFuzzDeepRunTests][com.ditchoom.socket.quic.sim.fuzz.SimFuzzDeepRunTests] pattern — a knob
 * with a CI-sized default, never a gate, so the lane always exercises the code path and can never
 * report green on a test it silently did not run).
 *
 * ```
 * QUIC_MEMORY_SOAK_READS=50000 ./gradlew :socket-quic-quiche:jvmTest --rerun \
 *     --tests '*QuicStreamReadMemorySoakTests*'
 * ```
 */
class QuicStreamReadMemorySoakTests {
    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            // Comfortably longer than the whole soak, so a slow runner cannot idle the connection out
            // mid-measurement and turn a memory verdict into a timing one.
            idleTimeout = 5.minutes,
            // The soak is one long-lived stream; without this each read would carry the
            // request/response default deadline instead of the connection's.
            persistentStreams = true,
        )

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    @Test
    fun theNativeFootprintIsFlatAcrossThousandsOfScopedStreamReads() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(QuicStreamReadMemorySoakTests::class) {
                val reads = System.getenv("QUIC_MEMORY_SOAK_READS")?.toIntOrNull() ?: MEASURED_READS
                val meter = NativeFootprintMeter.forThisProcess()
                val tier = DefaultBufferTier.resolve()
                withTimeout(SOAK_BUDGET) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
                        // Echo every chunk straight back inside the read scope: `write` is zero-copy and
                        // takes no ownership, the scope releases on the way out. This is the eleven-times
                        // -misused shape (#416) written the way that cannot leak.
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    try {
                                        while (true) {
                                            val echoed =
                                                stream.read(OP_DEADLINE) { bytes ->
                                                    stream.writeFully(bytes, OP_DEADLINE)
                                                }
                                            if (echoed !is ScopedRead.Data) break
                                        }
                                    } finally {
                                        stream.close()
                                    }
                                }
                            }

                        try {
                            // The wrapper's `timeout` bounds connect + block + close TOGETHER, so it must be
                            // the soak's own budget: the default 15s is a 1.5x margin over a healthy lane's
                            // 8-10s and a slow runner crosses it, turning a memory verdict into a timing one
                            // (the same trap `idleTimeout` above was raised to avoid, one layer up).
                            withQuicConnection("127.0.0.1", port, options, timeout = SOAK_BUDGET) {
                                val stream = openStream()
                                // One send buffer for the whole soak: it is caller-owned and reused, so
                                // nothing the *write* side allocates can drift into the read-side number.
                                val out = bufferFactory.allocate(PAYLOAD.length)
                                try {
                                    repeat(WARMUP_READS) { stream.echoRound(out) }

                                    // Baseline AFTER the warm-up: class loading, JIT and both drivers'
                                    // pools have settled, so what the measured phase adds is the read
                                    // path and the allocator's response to it.
                                    settleAndCollect()
                                    val before = sampleFootprint("before", meter)

                                    var echoedBytes = 0L
                                    repeat(reads) { echoedBytes += stream.echoRound(out) }

                                    val duringRun = sampleFootprint("in-run", meter)
                                    settleAndCollect()
                                    val after = sampleFootprint("after-gc", meter)

                                    // The soak is only evidence if it actually moved bytes; a stream that
                                    // silently stopped echoing would otherwise report a beautifully flat
                                    // footprint and pass.
                                    assertEquals(
                                        reads.toLong() * PAYLOAD.length,
                                        echoedBytes,
                                        "the soak did not echo what it sent, so its footprint proves nothing",
                                    )

                                    val growthKb = after.nativeKb - before.nativeKb
                                    val liveBufferGrowth = after.directPoolCount - before.directPoolCount
                                    val perReadBytes = growthKb * 1024.0 / reads
                                    val report =
                                        "reads=$reads payload=${PAYLOAD.length}B " +
                                            "readBuffer=${QuicheDriver.STREAM_READ_BUFFER_SIZE}B tier=$tier " +
                                            "meter=${meter.description}\n  $before\n  $duringRun\n  $after\n" +
                                            "  growth=${growthKb}kB (${"%.1f".format(perReadBytes)} B/read), " +
                                            "bound=${MAX_GROWTH_KB}kB; liveDirectBuffers=+$liveBufferGrowth, " +
                                            "bound=$MAX_LIVE_DIRECT_BUFFER_GROWTH"
                                    println("[538-soak] $report")
                                    assertTrue(
                                        growthKb <= MAX_GROWTH_KB,
                                        "the process's native footprint grew across $reads stream reads — a read " +
                                            "buffer is not being released, so its pool slot never returns and every " +
                                            "later read allocates fresh (#538).\n  $report",
                                    )
                                    // The JVM's own accounting of the same fact, and much sharper: one live
                                    // direct buffer per unreleased read, on both peers, with no OS or
                                    // allocator jitter in it. Second rather than first because it reads one
                                    // factory's implementation detail — see [NativeFootprintMeter].
                                    assertTrue(
                                        liveBufferGrowth <= MAX_LIVE_DIRECT_BUFFER_GROWTH,
                                        "the JVM is holding $liveBufferGrowth more live direct buffers than before " +
                                            "$reads stream reads — one per read buffer nobody released (#538).\n  $report",
                                    )
                                } finally {
                                    out.freeIfNeeded()
                                    stream.close()
                                }
                            }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    /**
     * One lock-step round trip: write [PAYLOAD], read it back through the scoped read, return how many
     * bytes came back.
     *
     * Loops until the whole payload has arrived rather than assuming one read per write — a QUIC stream
     * is a byte stream, and a test that silently accepted a short read would drift out of lock-step and
     * fail as a timeout thousands of rounds later.
     */
    private suspend fun QuicByteStream.echoRound(out: PlatformBuffer): Int {
        out.resetForWrite()
        out.writeString(PAYLOAD, Charset.UTF8)
        out.resetForRead()
        writeFully(out, OP_DEADLINE)
        var received = 0
        while (received < PAYLOAD.length) {
            when (val chunk = read(OP_DEADLINE) { it.remaining() }) {
                is ScopedRead.Data -> received += chunk.value
                ScopedRead.End -> error("the peer finished the stream mid-soak after $received bytes of this round")
                ScopedRead.Reset -> error("the peer reset the stream mid-soak after $received bytes of this round")
            }
        }
        return received
    }

    private companion object {
        /** Small on purpose: the read buffer is 64 KB whatever the payload, and that is what leaks. */
        const val PAYLOAD = "probe-538;"

        /** Past the JIT's compilation of this loop and past both drivers' pool fill. */
        const val WARMUP_READS = 500

        /**
         * Sized so the two curves separate rather than merely differ. The leak is **linear** in reads
         * (64 KB per read, on both in-process peers); the residual noise is an allocator/JIT high-water
         * mark that **saturates** — measured at 22 MB after 3000 reads and still only 32 MB after
         * 12 000. At 10 000 reads that is ~1.3 GB of leak against ~30 MB of noise, so [MAX_GROWTH_KB]
         * can sit comfortably clear of both. Costs ~11 s of loopback traffic.
         */
        const val MEASURED_READS = 10_000

        /**
         * Observed noise floor across repeat runs on a quiet macOS / JDK 21 host: see the class KDoc.
         * The bound is ~5x the worst observed noise and ~10x below the leak it guards, so ordinary
         * jitter cannot reach it and the defect cannot hide under it.
         */
        const val MAX_GROWTH_KB = 128L * 1024L

        /**
         * Live direct buffers the process may gain over the measured phase. Observed: **0** across
         * repeat healthy runs — the count sat at 34 from before to after — against +2 per read while
         * leaking. 256 leaves room for chunks legitimately in flight at the sampling instant, and is
         * still well under what a single leaking second produces.
         */
        const val MAX_LIVE_DIRECT_BUFFER_GROWTH = 256L

        /** Deadline for one read or write. Generous: the assertion here is about bytes, not latency. */
        val OP_DEADLINE: Duration = 20.seconds

        /** Whole-test budget, sized for the default [MEASURED_READS] on a loaded CI runner. */
        val SOAK_BUDGET: Duration = 4.minutes
    }
}
