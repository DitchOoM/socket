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
 * **A bounded stream read or write that completes before its bound wakes no timer thread** (the Kotlin/Native
 * twin of the JVM's `StreamDeadlineWakeupTests`).
 *
 * A busy stream arms a deadline and disarms it microseconds later, on every read and write. The timer thread
 * needs to wake only for a deadline earlier than the one it already sleeps towards; a thread that went idle
 * the moment everything armed had been disarmed is woken by the next arming, which is a wake-up per
 * operation. The property is counted through [NativeDeadlineTimer.passes], one per wake-up, so it is a count
 * and not a timing: N operations give a handful of passes at most, whatever N is.
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

        /**
         * Round trips on a real connection: the connection's own deadlines (the driver's wake, loss recovery) are armed on this
         * timer too, so the bound's share is read as a difference against the same trips with nothing armed. It was 5 or so when
         * measured (about 25 passes either way); a policy that sleeps towards every disarmed deadline, short driver timers
         * included, measured 900 against 730, and one wake per operation would be 500 or more.
         */
        const val AT_MOST_REAL = 60
    }
}
