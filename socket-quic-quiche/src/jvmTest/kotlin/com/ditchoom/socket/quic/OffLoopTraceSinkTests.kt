package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.OffLoopTraceSink
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [OffLoopTraceSink]: the application's trace sink is called off the thread that records, in order, and
 * a sink that falls behind loses the oldest events, is told how many, and never slows the recorder.
 */
class OffLoopTraceSinkTests {
    private fun event(n: Int) = TraceEvent.Error(n.toLong().nanoseconds, "test.Event", "event-$n")

    @Test
    fun eventsReachTheSinkInOrderOnAnotherThreadAndFlushWaitsForThem() =
        runBlocking {
            val seen = Collections.synchronizedList(mutableListOf<String>())
            val threads = Collections.synchronizedSet(mutableSetOf<Thread>())
            val sink =
                OffLoopTraceSink(
                    TraceSink { e ->
                        threads.add(Thread.currentThread())
                        seen.add((e as TraceEvent.Error).message)
                    },
                    Dispatchers.IO,
                )
            repeat(5_000) { sink.emit(event(it)) }
            assertTrue(sink.flush(10.seconds), "flush must see every queued event delivered")
            assertEquals(List(5_000) { "event-$it" }, seen)
            assertTrue(Thread.currentThread() !in threads, "the sink ran on the recording thread")
            assertEquals(0, sink.dropped)
        }

    @Test
    fun aSinkThatFallsBehindLosesTheOldestIsToldHowManyAndNeverBlocksTheRecorder() =
        runBlocking {
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val seen = Collections.synchronizedList(mutableListOf<TraceEvent>())
            val sink =
                OffLoopTraceSink(
                    TraceSink { e ->
                        entered.countDown()
                        release.await()
                        seen.add(e)
                    },
                    Dispatchers.IO,
                    capacity = CAPACITY,
                )
            sink.emit(event(0))
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the first event never reached the sink")
            // The sink is now blocked holding event 0. Every emit must still return at once.
            val started = System.nanoTime()
            for (n in 1..TOTAL) sink.emit(event(n))
            val emitting = (System.nanoTime() - started).nanoseconds
            assertTrue(emitting < 2.seconds, "$TOTAL emits behind a blocked sink took $emitting: emit must never wait for the sink")
            release.countDown()
            assertTrue(sink.flush(10.seconds))

            // Event 0, then one overflow report, then the newest CAPACITY events, oldest first.
            val messages = seen.map { (it as? TraceEvent.Error)?.message }
            assertEquals("event-0", messages.first())
            val report = assertIs<TraceEvent.Drop>(seen[1])
            assertEquals(OffLoopTraceSink.OVERFLOW_TYPE, report.type)
            val expectedDropped = TOTAL - CAPACITY
            assertEquals(expectedDropped.toLong(), sink.dropped)
            assertTrue(report.message.startsWith("$expectedDropped "), "the report must say how many: ${report.message}")
            assertEquals(((TOTAL - CAPACITY + 1)..TOTAL).map { "event-$it" }, messages.drop(2))
        }

    @Test
    fun aSinkThatThrowsLosesOnlyThatEvent() =
        runBlocking {
            val seen = Collections.synchronizedList(mutableListOf<String>())
            val sink =
                OffLoopTraceSink(
                    TraceSink { e ->
                        val m = (e as TraceEvent.Error).message
                        if (m == "event-1") error("a broken sink")
                        seen.add(m)
                    },
                    Dispatchers.IO,
                )
            repeat(3) { sink.emit(event(it)) }
            assertTrue(sink.flush(5.seconds))
            assertEquals(listOf("event-0", "event-2"), seen)
        }

    @Test
    fun flushGivesUpOnAStuckSinkWithinItsBound() =
        runBlocking {
            val release = CountDownLatch(1)
            val sink = OffLoopTraceSink(TraceSink { release.await() }, Dispatchers.IO)
            sink.emit(event(0))
            sink.emit(event(1))
            val started = System.nanoTime()
            val done = sink.flush(100.milliseconds)
            val waited = (System.nanoTime() - started).nanoseconds
            release.countDown()
            assertEquals(false, done)
            assertTrue(waited < 5.seconds, "flush waited $waited on a stuck sink")
        }

    private companion object {
        const val CAPACITY = 64
        const val TOTAL = 20_000
    }
}
