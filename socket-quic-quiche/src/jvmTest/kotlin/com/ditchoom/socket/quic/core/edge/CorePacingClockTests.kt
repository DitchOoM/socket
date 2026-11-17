package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.core.testing.LoopScheduling
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The time quiche's pacer gives each datagram is on the clock the loop reads: a datagram is due a pacing interval from now,
 * never a different clock's reading, which the connection would wait on or ignore.
 */
class CorePacingClockTests {
    @Test
    fun pacerTimesOfARealTransferAreOnTheLoopsClock() =
        runBlocking {
            skipOnMissingNativeLib(CorePacingClockTests::class) {
                val api = RecordingApi(loadQuicheApi())
                val scheduled = LoopScheduling.snapshot()
                // The client's own pacer times: the core server is step 4, and every sent datagram is timed alike.
                withEchoServer { port ->
                    val client = connectCore(port, api = api)
                    val stream = client.connection.openStream()
                    var received = 0L
                    coroutineScope {
                        launch {
                            val chunk = BufferFactory.Default.allocate(CHUNK).apply { repeat(CHUNK) { writeByte(it.toByte()) } }
                            repeat(TOTAL / CHUNK) {
                                chunk.position(0)
                                chunk.setLimit(CHUNK)
                                stream.writeFully(chunk, 30.seconds)
                            }
                        }
                        while (received < TOTAL) {
                            val result = stream.read(30.seconds)
                            if (result !is ReadResult.Data) fail("the echo ended after $received of $TOTAL bytes: $result")
                            received += result.buffer.remaining()
                            result.buffer.freeIfNeeded()
                        }
                    }
                    client.connection.close(QuicError.NoError)
                }
                println(
                    "[pacing clock] ${api.reads.get()} datagrams; earliest ${api.earliest.get() / 1_000} us, latest ${api.latest.get() / 1_000} us from now",
                )
                assertTrue(api.reads.get() > 1_000, "a transfer of ${TOTAL / 1_048_576} MiB writes more datagrams than that")
                assertTrue(api.earliest.get() > -5_000_000_000L, "a due time is not in the far past: ${api.earliest.get()} ns")
                // A pacer schedules a flight at the pace of one round trip, so how far ahead a datagram may be due scales with the
                // largest round trip the connection measured; a clock of another origin or unit is off by hours or by a thousand-fold.
                val bound = 1_000_000_000L + 2 * api.largestRttNanos.get()
                assertTrue(
                    api.latest.get() < bound,
                    "a due time is within a second plus two round trips of now: ${api.latest.get()} ns, largest round trip " +
                        "${api.largestRttNanos.get()} ns; scheduling ${LoopScheduling.since(scheduled)}",
                )
            }
        }

    /** Remembers the earliest and the latest due time seen, relative to the clock at the moment it was read, and the largest round trip. */
    private class RecordingApi(
        private val real: QuicheApi,
    ) : QuicheApi by real {
        val reads = AtomicLong()
        val earliest = AtomicLong(Long.MAX_VALUE)
        val latest = AtomicLong(Long.MIN_VALUE)
        val largestRttNanos = AtomicLong()

        override fun connSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            sendInfo: QuicheSendInfo,
        ): Int {
            real.connPathStats(conn, 0)?.let { largestRttNanos.accumulateAndGet(it.rtt.inWholeNanoseconds, ::maxOf) }
            return real.connSend(conn, buf, bufLen, sendInfo)
        }

        override fun sendInfoAtNanos(info: QuicheSendInfo): Long {
            val due = real.sendInfoAtNanos(info)
            val fromNow = due - System.nanoTime()
            reads.incrementAndGet()
            earliest.accumulateAndGet(fromNow, ::minOf)
            latest.accumulateAndGet(fromNow, ::maxOf)
            return due
        }
    }

    private companion object {
        const val TOTAL = 16 * 1024 * 1024
        const val CHUNK = 65_536
    }
}
