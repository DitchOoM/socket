package com.ditchoom.socket.quic.core.edge

import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The wait the allocation gates use for a handler to return: bounded by time, so a handler that a loaded host runs a
 * few hundred milliseconds late still counts, whatever the host's speed.
 */
class CountAwaitedTests {
    @Test
    fun aCountReachedWellWithinTheBoundIsReachedHoweverFastTheWaiterLoops() =
        runBlocking {
            val count = AtomicLong()
            val late =
                thread {
                    Thread.sleep(LATE.inWholeMilliseconds)
                    count.incrementAndGet()
                }
            val awaited = awaitCount(count, 1, 10.seconds)
            late.join()
            assertIs<CountAwaited.Reached>(awaited, "the count arrived $LATE late, inside a 10 s bound")
            Unit
        }

    @Test
    fun aCountNeverReachedIsReportedOnceTheBoundPassed() =
        runBlocking {
            val awaited = awaitCount(AtomicLong(), 1, 200.milliseconds)
            assertIs<CountAwaited.NotReached>(awaited)
            assertTrue(awaited.waited >= 200.milliseconds, "it gave up after ${awaited.waited}, before its bound")
        }

    private companion object {
        val LATE = 3.seconds
    }
}
