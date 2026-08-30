package com.ditchoom.socket.quic

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The device-side guard for #538: **ART's native heap stays flat across thousands of QUIC stream
 * reads.**
 *
 * The JVM member of this pair ([QuicStreamReadMemorySoakTests]) measures the resident set, which is
 * the right instrument on a host. Here the right instrument is
 * [Debug.getNativeHeapAllocatedSize] — the number ART itself is tracking, and the number whose
 * runaway growth ended the 2026-08-30 walk at VmSize 20.8 GB. RSS on a device is not usable for this:
 * the kernel reclaims aggressively under memory pressure and the app is not the only thing moving it.
 *
 * This is the lane that mattered most, because Android is the platform that actually shipped the
 * crash: it is the only target that runs quiche over JNI, and [DeviceHandoffProbe] — the rig that
 * died — lives beside this file. That probe now uses the scoped read; this test is what will notice
 * if a future edit takes it back out, without needing a five-hour walk to find out.
 *
 * Deliberately smaller than the JVM soak ([READS] vs 10 000): a device lane is slower and shares the
 * machine with the rest of the instrumented suite. [MAX_GROWTH_BYTES] is scaled to match — a leak is
 * ~128 kB per round trip across the two in-process peers, so [READS] reads leak ~190 MB against a
 * bound of 24 MB, while the healthy path returns every buffer to a pool capped at 16.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicStreamReadMemorySoakTests {
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 5.minutes,
            persistentStreams = true,
        )

    private val tlsConfig get() = AndroidTestCerts.tlsConfig

    @Test
    fun theNativeHeapIsFlatAcrossThousandsOfScopedStreamReads() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(AndroidQuicStreamReadMemorySoakTests::class) {
                withTimeout(SOAK_BUDGET) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    try {
                                        while (true) {
                                            // Echo inside the read scope: write takes no ownership,
                                            // the scope releases the read buffer on exit (#538).
                                            if (stream.read(OP_DEADLINE) { stream.writeFully(it, OP_DEADLINE) } !is ScopedRead.Data) break
                                        }
                                    } finally {
                                        stream.close()
                                    }
                                }
                            }
                        try {
                            withQuicConnection("127.0.0.1", port, testQuicOptions, timeout = 15.seconds) {
                                val stream = openStream()
                                val out = bufferFactory.allocate(PAYLOAD.length)
                                try {
                                    repeat(WARMUP_READS) { stream.echoRound(out) }
                                    val before = settledNativeHeap()

                                    var echoed = 0L
                                    repeat(READS) { echoed += stream.echoRound(out) }

                                    val after = settledNativeHeap()
                                    assertEquals(
                                        READS.toLong() * PAYLOAD.length,
                                        echoed,
                                        "the soak did not echo what it sent, so its footprint proves nothing",
                                    )
                                    val growth = after - before
                                    val report =
                                        "reads=$READS nativeHeap ${before}B -> ${after}B " +
                                            "(+${growth}B, ${growth / READS}B/read), bound=${MAX_GROWTH_BYTES}B"
                                    println("[538-soak-android] $report")
                                    assertTrue(
                                        growth <= MAX_GROWTH_BYTES,
                                        "ART's native heap grew across $READS stream reads — a read buffer is not " +
                                            "being released, so its pool slot never returns and every later read " +
                                            "allocates fresh (#538). $report",
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
     * Collect, let ART's finalizer/reference threads drain, then read the native heap — so the number
     * cannot be waved away as "the collector had not run yet". On the tier this runs on that settle
     * genuinely could reclaim a dropped buffer, and the measurement is that it does not: the QUIC read
     * path allocates from `BufferFactory.network()` (`deterministic()`), whose memory is released by
     * an explicit `freeNativeMemory()` and by nothing else.
     */
    private fun settledNativeHeap(): Long {
        repeat(3) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(SETTLE_MILLIS)
        }
        return Debug.getNativeHeapAllocatedSize()
    }

    /** One lock-step round trip; loops until the whole payload is back, so a split read cannot desync. */
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
        const val PAYLOAD = "probe-538;"
        const val WARMUP_READS = 200
        const val READS = 1500
        const val MAX_GROWTH_BYTES = 24L * 1024L * 1024L
        const val SETTLE_MILLIS = 300L
        val OP_DEADLINE: Duration = 20.seconds
        val SOAK_BUDGET: Duration = 5.minutes
    }
}
