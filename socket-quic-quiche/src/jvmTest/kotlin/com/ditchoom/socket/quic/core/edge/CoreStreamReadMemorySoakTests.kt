package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DefaultBufferTier
import com.ditchoom.socket.quic.NativeFootprintMeter
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.sampleFootprint
import com.ditchoom.socket.quic.settleAndCollect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The process's native footprint stays flat across thousands of stream reads on a real core client and echo server.
 *
 * The read path hands its buffers back to the connection, so a read whose buffer is never released shows up as pages the
 * process holds and live direct buffers: two per round trip on this in-process pair, about 142 kB per read. After a
 * warm-up the test takes a baseline, runs [MEASURED_READS] echo rounds on one stream, collects, and compares the resident
 * set minus the committed heap, and the count of live direct buffers, with the baseline. The bounds sit about five times
 * above the growth a healthy run shows and about ten times below what a leak adds at this read count.
 * `QUIC_MEMORY_SOAK_READS` scales the run for an investigation; the default is what the lane runs.
 *
 * It times a loop against the wall clock and measures the process, so it runs in the timing-sensitive task, alone.
 */
class CoreStreamReadMemorySoakTests {
    private val loops = LoopSet(1)

    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            // Longer than the whole soak, so a slow runner cannot idle the connection out mid-measurement.
            idleTimeout = 5.minutes,
        )

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun theNativeFootprintIsFlatAcrossThousandsOfScopedStreamReads() =
        runBlocking {
            val reads = System.getenv("QUIC_MEMORY_SOAK_READS")?.toIntOrNull() ?: MEASURED_READS
            val meter = NativeFootprintMeter.forThisProcess()
            val tier = DefaultBufferTier.resolve()
            withTimeout(SOAK_BUDGET) {
                withCoreEchoServer(options = options) { server ->
                    val client = connectCore(server.port, loops = loops, options = options)
                    val stream = client.connection.openStream()
                    // One send buffer for the whole soak, caller-owned and reused: nothing the write side allocates drifts in.
                    val out = BufferFactory.network().allocate(PAYLOAD.length)
                    try {
                        repeat(WARMUP_READS) { stream.echoRound(out) }
                        settleAndCollect()
                        val before = sampleFootprint("before", meter)

                        var echoedBytes = 0L
                        repeat(reads) { echoedBytes += stream.echoRound(out) }

                        val duringRun = sampleFootprint("in-run", meter)
                        settleAndCollect()
                        val after = sampleFootprint("after-gc", meter)

                        // A stream that silently stopped echoing would report a flat footprint and pass.
                        assertEquals(reads.toLong() * PAYLOAD.length, echoedBytes, "the soak did not echo what it sent")

                        val growthKb = after.nativeKb - before.nativeKb
                        val liveBufferGrowth = after.directPoolCount - before.directPoolCount
                        val report =
                            "reads=$reads payload=${PAYLOAD.length}B tier=$tier meter=${meter.description}\n  $before\n  $duringRun\n  $after\n" +
                                "  growth=${growthKb}kB (${"%.1f".format(growthKb * 1024.0 / reads)} B/read), bound=${MAX_GROWTH_KB}kB; " +
                                "liveDirectBuffers=+$liveBufferGrowth, bound=$MAX_LIVE_DIRECT_BUFFER_GROWTH"
                        println("[core read soak] $report")
                        assertTrue(growthKb <= MAX_GROWTH_KB, "the native footprint grew across $reads stream reads: a read buffer is not released.\n  $report")
                        assertTrue(
                            liveBufferGrowth <= MAX_LIVE_DIRECT_BUFFER_GROWTH,
                            "$liveBufferGrowth more live direct buffers than before $reads stream reads.\n  $report",
                        )
                    } finally {
                        out.freeIfNeeded()
                        stream.close()
                        client.connection.close(QuicError.NoError)
                    }
                }
            }
        }

    /** One lock-step round trip: writes [PAYLOAD], reads it back through the scoped read, and returns how many bytes came back. */
    private suspend fun QuicByteStream.echoRound(out: PlatformBuffer): Int {
        out.resetForWrite()
        out.writeString(PAYLOAD, Charset.UTF8)
        out.resetForRead()
        writeFully(out, OP_DEADLINE)
        var received = 0
        while (received < PAYLOAD.length) {
            when (val chunk = read(OP_DEADLINE) { it.remaining() }) {
                is ScopedReadResult.Data -> received += chunk.value
                ScopedReadResult.End -> error("the peer finished the stream mid-soak after $received bytes of this round")
                ScopedReadResult.Reset -> error("the peer reset the stream mid-soak after $received bytes of this round")
            }
        }
        return received
    }

    private companion object {
        /** Small on purpose: the read buffer's size does not depend on the payload, and that is what leaks. */
        const val PAYLOAD = "probe-538;"

        /** Past the JIT's compilation of the loop and the pools' fill. */
        const val WARMUP_READS = 500

        /** Enough that a per-read leak separates from the saturating start-up cost. */
        const val MEASURED_READS = 10_000

        /** About five times the growth a healthy run shows and ten times below what a leak adds. */
        const val MAX_GROWTH_KB = 128L * 1024L

        /** A healthy run shows none; a leak adds two per read. */
        const val MAX_LIVE_DIRECT_BUFFER_GROWTH = 256L

        val OP_DEADLINE: Duration = 20.seconds

        val SOAK_BUDGET: Duration = 4.minutes
    }
}
