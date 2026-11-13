package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * **A bounded stream read or write that completes before its bound wakes no timer thread.**
 *
 * A busy stream arms a deadline and disarms it microseconds later on every read and write. The property is counted:
 * how many times the threads that fire timers went from running to waiting while N operations completed. A wake per
 * operation gives N or more, a timer that wakes only for an earlier deadline a handful whatever N is, so [AT_MOST] is
 * a constant far below [OPERATIONS].
 */
class StreamDeadlineWakeupTests {
    @Test
    fun completedBoundedStreamOperationsWakeNoTimerThread(): Unit =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(StreamDeadlineWakeupTests::class) {
                withTimeout(60.seconds) {
                    val options = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 20.seconds)
                    val tls = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))
                    withQuicServer(port = 0, tlsConfig = tls, quicOptions = options) {
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    while (true) {
                                        val echoed = stream.read(30.seconds) { chunk -> stream.write(chunk, 30.seconds) }
                                        if (echoed !is ScopedRead.Data) break
                                    }
                                    stream.close()
                                }
                            }
                        try {
                            withQuicConnection("localhost", port, options, timeout = 20.seconds) {
                                val stream = openStream()
                                val request = BufferFactory.Default.allocate(64)

                                suspend fun roundTrip() {
                                    request.resetForWrite()
                                    repeat(64) { request.writeByte(1) }
                                    request.resetForRead()
                                    stream.write(request, 30.seconds)
                                    stream.read(30.seconds) { it.remaining() }
                                }
                                // Warm up (the connection, the stream, the timers' threads), then let them settle.
                                repeat(50) { roundTrip() }
                                kotlinx.coroutines.delay(300)
                                val before = TimerThreadWaits.sample()
                                repeat(OPERATIONS) { roundTrip() }
                                val wakes = TimerThreadWaits.sample() - before
                                assertTrue(
                                    wakes <= AT_MOST,
                                    "$OPERATIONS round trips (each a bounded write and a bounded read on both ends, all completing " +
                                        "well inside their 30 s bound) woke the threads that fire timers $wakes times; a deadline " +
                                        "that is disarmed before it is due must not wake one, so at most $AT_MOST were expected " +
                                        "(${TimerThreadWaits.describe()})",
                                )
                                stream.shutdownSend()
                                stream.close()
                            }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    @Test
    fun completedBoundsOnTheCallersDispatcherWakeNoTimerThread(): Unit =
        runBlocking {
            repeat(50) { withStreamDeadline(30.seconds) { } }
            kotlinx.coroutines.delay(300)
            val before = TimerThreadWaits.sample()
            repeat(OPERATIONS * 4) { withStreamDeadline(30.seconds) { 1 } }
            val wakes = TimerThreadWaits.sample() - before
            assertTrue(
                wakes <= AT_MOST,
                "${OPERATIONS * 4} completed bounds woke the threads that fire timers $wakes times, at most $AT_MOST were " +
                    "expected (${TimerThreadWaits.describe()})",
            )
        }

    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    /**
     * How many times the threads that fire timers have gone from running to waiting. JVM thread-wait
     * counts include `LockSupport.park`, which is what an idle timer thread, a kotlinx `DefaultExecutor`
     * and a `ScheduledThreadPoolExecutor` worker all wait with.
     */
    private object TimerThreadWaits {
        private val mx = ManagementFactory.getThreadMXBean()
        private val NAMES = setOf("quic-deadline-timer", "kotlinx.coroutines.DefaultExecutor")

        private fun timerThreads() = Thread.getAllStackTraces().keys.filter { it.name in NAMES }

        fun sample(): Long = timerThreads().sumOf { mx.getThreadInfo(it.id)?.waitedCount?.coerceAtLeast(0) ?: 0L }

        fun describe(): String = timerThreads().joinToString { "${it.name}=${mx.getThreadInfo(it.id)?.waitedCount}" }
    }

    private companion object {
        const val OPERATIONS = 500
        const val AT_MOST = 25L
    }
}
