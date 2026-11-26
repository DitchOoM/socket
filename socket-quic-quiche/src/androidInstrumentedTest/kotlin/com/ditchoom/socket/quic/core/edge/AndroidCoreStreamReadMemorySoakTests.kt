package com.ditchoom.socket.quic.core.edge

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * ART's native heap stays flat across thousands of stream reads on a real core client and echo server: the device-side
 * member of the JVM soak, which measures the resident set. A device's resident set is not usable for this, so the number
 * is [Debug.getNativeHeapAllocatedSize], after a collection and a drain of the finalizer threads. A read whose buffer is
 * never released leaks about 128 kB per round trip across the two in-process peers: [READS] reads leak about 190 MB
 * against a bound of 24 MB.
 */
@RunWith(AndroidJUnit4::class)
class AndroidCoreStreamReadMemorySoakTests {
    private val loops = LoopSet(1)

    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 5.minutes)

    @After
    fun closeLoops() = loops.close()

    @Test
    fun theNativeHeapIsFlatAcrossThousandsOfScopedStreamReads() =
        runBlocking {
            withTimeout(SOAK_BUDGET) {
                withCoreEchoServer(options = options) { server ->
                    val client = connectCore(server.port, loops = loops, options = options)
                    val stream = client.connection.openStream()
                    val out = BufferFactory.network().allocate(PAYLOAD.length)
                    try {
                        repeat(WARMUP_READS) { stream.echoRound(out) }
                        val before = settledNativeHeap()

                        var echoed = 0L
                        repeat(READS) { echoed += stream.echoRound(out) }

                        val after = settledNativeHeap()
                        assertEquals(READS.toLong() * PAYLOAD.length, echoed, "the soak did not echo what it sent")
                        val growth = after - before
                        assertTrue(
                            growth <= MAX_GROWTH_BYTES,
                            "ART's native heap grew across $READS stream reads: a read buffer is not released. " +
                                "${before}B -> ${after}B (+${growth}B, ${growth / READS}B/read), bound=${MAX_GROWTH_BYTES}B",
                        )
                    } finally {
                        out.freeIfNeeded()
                        stream.close()
                        client.connection.close(QuicError.NoError)
                    }
                }
            }
        }

    /** Collects, lets ART's finalizer and reference threads drain, then reads the native heap. */
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
                is ScopedReadResult.Data -> received += chunk.value
                ScopedReadResult.End -> error("the peer finished the stream mid-soak after $received bytes of this round")
                ScopedReadResult.Reset -> error("the peer reset the stream mid-soak after $received bytes of this round")
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
