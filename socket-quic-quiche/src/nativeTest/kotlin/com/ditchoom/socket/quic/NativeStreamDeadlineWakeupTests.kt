package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * **A bounded stream read or write that completes before its bound wakes no timer thread** (the Kotlin/Native twin of
 * the JVM's `StreamDeadlineWakeupTests`).
 *
 * The property is counted through [NativeDeadlineTimer.passes], one per wake-up: N operations make a handful of
 * passes at most, whatever N is. On a real connection the driver's own deadlines are armed on the same timer, so
 * there the bounds' share is read as the difference against the same round trips with no bound armed.
 */
abstract class NativeStreamDeadlineWakeupTests {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Waits until the shared timer thread has made no pass for a while, so the count starts from rest. */
    private suspend fun settle() {
        var last = -1
        var stable = 0
        while (stable < 5) {
            delay(40.milliseconds)
            val now = NativeDeadlineTimer.Shared.passes.value
            if (now == last) stable++ else stable = 0
            last = now
        }
    }

    @Test
    fun completedBoundsOnTheCallersDispatcherWakeNoTimerThread() =
        runQuicTest(timeout = 60.seconds) {
            repeat(50) { withStreamDeadline(30.seconds) { } }
            settle()
            val before = NativeDeadlineTimer.Shared.passes.value
            repeat(OPERATIONS * 10) { withStreamDeadline(30.seconds) { 1 } }
            val wakes = NativeDeadlineTimer.Shared.passes.value - before
            assertTrue(
                wakes <= AT_MOST,
                "${OPERATIONS * 10} completed bounds made the timer thread pass over its queue $wakes times; at most $AT_MOST were expected",
            )
        }

    @Test
    fun completedBoundedStreamOperationsWakeNoTimerThread() =
        runQuicTest(timeout = 120.seconds) {
            val options = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 60.seconds)
            withQuicServer(port = 0, host = "127.0.0.1", tlsConfig = testTlsConfig(), quicOptions = options) {
                val serverJob =
                    launch {
                        connections {
                            coroutineScope {
                                streams().collect { stream ->
                                    launch {
                                        try {
                                            while (true) {
                                                when (val r = stream.read(30.seconds)) {
                                                    is ReadResult.Data ->
                                                        try {
                                                            stream.writeFully(r.buffer, 30.seconds)
                                                        } finally {
                                                            r.buffer.freeIfNeeded()
                                                        }
                                                    ReadResult.End, ReadResult.Reset -> break
                                                }
                                            }
                                            stream.shutdownSend()
                                        } finally {
                                            stream.close()
                                        }
                                    }
                                }
                            }
                        }
                    }
                try {
                    withQuicConnection("127.0.0.1", port, options, timeout = 60.seconds) {
                        val stream = openStream()
                        val buf = bufferFactory.allocate(64)

                        suspend fun roundTrip(bound: kotlin.time.Duration) {
                            buf.resetForWrite()
                            repeat(64) { buf.writeByte('r'.code.toByte()) }
                            buf.resetForRead()
                            stream.writeFully(buf, bound)
                            var got = 0
                            while (got < 64) {
                                when (val r = stream.read(bound)) {
                                    is ReadResult.Data ->
                                        try {
                                            got += r.buffer.remaining()
                                        } finally {
                                            r.buffer.freeIfNeeded()
                                        }
                                    ReadResult.End, ReadResult.Reset -> error("the echo ended after $got bytes")
                                }
                            }
                        }
                        repeat(50) { roundTrip(30.seconds) }

                        // The same round trips with no bound armed (an infinite one arms nothing) and with a 30 s bound on every
                        // read and write. The connection's own deadlines (idle, loss recovery, the driver's wake) reach this
                        // timer too, on Native, and both runs include them; what the bounds add is the difference.
                        suspend fun passesFor(bound: kotlin.time.Duration): Int {
                            settle()
                            val before = NativeDeadlineTimer.Shared.passes.value
                            repeat(OPERATIONS) { roundTrip(bound) }
                            return NativeDeadlineTimer.Shared.passes.value - before
                        }
                        val unbounded = passesFor(kotlin.time.Duration.INFINITE)
                        val bounded = passesFor(30.seconds)
                        val unboundedAgain = passesFor(kotlin.time.Duration.INFINITE)
                        val baseline = maxOf(unbounded, unboundedAgain)
                        assertTrue(
                            bounded <= baseline + AT_MOST_REAL,
                            "$OPERATIONS round trips with a 30 s bound on every read and write made the timer thread " +
                                "pass $bounded times, " +
                                "$unbounded and $unboundedAgain with no bound armed: the bounds added ${bounded - baseline}, " +
                                "more than the $AT_MOST_REAL that a deadline disarmed before it is due may cost",
                        )
                        stream.shutdownSend()
                        stream.close()
                    }
                } finally {
                    serverJob.cancel()
                }
            }
        }

    private companion object {
        const val OPERATIONS = 500
        const val AT_MOST = 25

        /** Passes the bounds may add over the same round trips with none armed; one wake per operation would add [OPERATIONS]. */
        const val AT_MOST_REAL = 60
    }
}
