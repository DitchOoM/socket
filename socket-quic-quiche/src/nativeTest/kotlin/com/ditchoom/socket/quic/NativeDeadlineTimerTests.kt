package com.ditchoom.socket.quic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun DeadlineQueue.nextDueNanos(): Long =
    when (val next = nextDue()) {
        is NextDue.At -> next.nanos
        NextDue.None -> error("nothing is armed")
    }

/** [DeadlineQueue]: ordering, disarm and sweep, with the time passed in, so no clock decides anything. */
class DeadlineQueueTests {
    private val fired = mutableListOf<String>()

    private fun DeadlineQueue.armLogging(
        due: Long,
        name: String,
    ) = arm(due, Runnable { fired += name })

    @Test
    fun firesInDueOrderAndEqualDueTimesInTheOrderTheyWereArmed() {
        val q = DeadlineQueue()
        q.armLogging(30, "c")
        q.armLogging(10, "a1")
        q.armLogging(20, "b")
        q.armLogging(10, "a2")
        q.fireDue(25)
        assertEquals(listOf("a1", "a2", "b"), fired)
        q.fireDue(100)
        assertEquals(listOf("a1", "a2", "b", "c"), fired)
    }

    @Test
    fun nothingFiresBeforeItIsDue() {
        val q = DeadlineQueue()
        q.armLogging(10, "a")
        q.fireDue(9)
        assertEquals(emptyList(), fired)
        q.fireDue(10)
        assertEquals(listOf("a"), fired)
    }

    @Test
    fun aDisarmedDeadlineNeverFiresAndDisarmingAfterItFiredChangesNothing() {
        val q = DeadlineQueue()
        val a = q.armLogging(10, "a")
        val b = q.armLogging(10, "b")
        a.dispose()
        q.fireDue(10)
        assertEquals(listOf("b"), fired)
        b.dispose()
        q.fireDue(1_000)
        assertEquals(listOf("b"), fired)
    }

    @Test
    fun reArmingIsDisarmAndArmAndOnlyTheNewOneFires() {
        val q = DeadlineQueue()
        val old = q.armLogging(10, "old")
        old.dispose()
        q.armLogging(50, "new")
        q.fireDue(20)
        assertEquals(emptyList(), fired)
        assertEquals(50L, q.nextDueNanos())
        q.fireDue(50)
        assertEquals(listOf("new"), fired)
    }

    @Test
    fun nextDueSkipsDisarmedDeadlinesAndSaysWhenThereIsNone() {
        val q = DeadlineQueue()
        assertEquals(NextDue.None, q.nextDue())
        val a = q.armLogging(10, "a")
        q.armLogging(40, "b")
        a.dispose()
        assertEquals(40L, q.nextDueNanos())
    }

    @Test
    fun anActionThatArmsAnotherFiresItOnTheNextPassOnlyWhenDue() {
        val q = DeadlineQueue()
        q.arm(
            10,
            Runnable {
                fired += "first"
                q.armLogging(15, "second")
            },
        )
        q.fireDue(10)
        assertEquals(listOf("first"), fired)
        q.fireDue(15)
        assertEquals(listOf("first", "second"), fired)
    }

    @Test
    fun aSweepDropsDisarmedDeadlinesWhenTheyAreMostOfTheHeapAndKeepsTheLiveOnes() {
        val q = DeadlineQueue()
        val dead = (1..5_000).map { q.arm(1_000_000L + it, Runnable { fired += "dead$it" }) }
        val live = q.armLogging(2_000_000, "live")
        dead.forEach { it.dispose() }
        // The next arm drains the inbox into the heap, and the sweep runs with the disarmed ones in it.
        q.armLogging(3_000_000, "live2")
        assertEquals(2_000_000L, q.nextDueNanos())
        val census = q.census()
        assertTrue(census.size <= 3, "the heap holds only what is live, not ${census.size}")
        assertEquals(0, census.disarmed)
        q.fireDue(10_000_000)
        assertEquals(listOf("live", "live2"), fired)
        live.dispose()
    }
}

/** [NativeDeadlineTimer] on its thread, and the places the library arms deadlines through it. */
class NativeDeadlineTimerTests {
    private suspend fun <T> real(block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }

    @Test
    fun deadlinesFireInDueOrderOnTheTimerThread() =
        runTest {
            real {
                val timer = NativeDeadlineTimer()
                try {
                    val order = Channel<Int>(Channel.UNLIMITED)
                    timer.schedule(150.milliseconds) { order.trySend(3) }
                    timer.schedule(30.milliseconds) { order.trySend(1) }
                    timer.schedule(90.milliseconds) { order.trySend(2) }
                    val got = withTimeout(10.seconds) { listOf(order.receive(), order.receive(), order.receive()) }
                    assertEquals(listOf(1, 2, 3), got)
                } finally {
                    timer.close()
                }
            }
        }

    @Test
    fun aDisarmedDeadlineDoesNotFire() =
        runTest {
            real {
                val timer = NativeDeadlineTimer()
                try {
                    val fired = Channel<Int>(Channel.UNLIMITED)
                    val doomed = timer.schedule(50.milliseconds) { fired.trySend(1) }
                    timer.schedule(120.milliseconds) { fired.trySend(2) }
                    doomed.dispose()
                    assertEquals(2, withTimeout(10.seconds) { fired.receive() })
                    assertTrue(fired.tryReceive().isFailure, "the disarmed one fired too")
                } finally {
                    timer.close()
                }
            }
        }

    @Test
    fun aDeadlineArmedEarlierThanTheOneTheThreadSleepsUntilWakesIt() =
        runTest {
            real {
                val timer = NativeDeadlineTimer()
                try {
                    val far = timer.schedule(1.hours) { error("an hour has not passed") }
                    delay(100.milliseconds) // let the thread go to sleep until the far deadline
                    val fired = CompletableDeferred<Unit>()
                    timer.schedule(20.milliseconds) { fired.complete(Unit) }
                    withTimeout(10.seconds) { fired.await() }
                    far.dispose()
                } finally {
                    timer.close()
                }
            }
        }

    @Test
    fun aDeadlineArmedLaterThanTheOneTheThreadSleepsUntilFiresOnItsOwnTime() =
        runTest {
            real {
                val timer = NativeDeadlineTimer()
                try {
                    val order = Channel<Int>(Channel.UNLIMITED)
                    timer.schedule(60.milliseconds) { order.trySend(1) }
                    timer.schedule(200.milliseconds) { order.trySend(2) }
                    assertEquals(1, withTimeout(10.seconds) { order.receive() })
                    assertEquals(2, withTimeout(10.seconds) { order.receive() })
                } finally {
                    timer.close()
                }
            }
        }

    @Test
    fun timeoutsInsideTheTimedDispatcherFireOnTheTimer() =
        runTest {
            real {
                val timer = NativeDeadlineTimer()
                try {
                    withContext(timer.over(Dispatchers.Default)) {
                        assertNull(withTimeoutOrNull(50.milliseconds) { awaitCancellation() })
                        delay(20.milliseconds)
                        assertFailsWith<TimeoutCancellationException> { withTimeout(30.milliseconds) { awaitCancellation() } }
                        assertEquals("done", withTimeout(5.seconds) { "done" })
                    }
                } finally {
                    timer.close()
                }
            }
        }

    @Test
    fun aStreamDeadlineExpiresAsATimeoutAndPassesAResultThrough() =
        runTest {
            real {
                assertFailsWith<TimeoutCancellationException> { withStreamDeadline(50.milliseconds) { awaitCancellation() } }
                assertEquals(7, withStreamDeadline(5.seconds) { 7 })
                assertFailsWith<TimeoutCancellationException> { withStreamDeadline(0.milliseconds) { 1 } }
                // An exception of the block's own is not mistaken for the deadline.
                assertFailsWith<IllegalStateException> { withStreamDeadline(5.seconds) { error("own") } }
            }
        }

    @Test
    fun manyDeadlinesArmedAndDisarmedFromManyThreadsAllSettle() =
        runTest {
            real {
                val timer = NativeDeadlineTimer()
                try {
                    val fired = Channel<Int>(Channel.UNLIMITED)
                    coroutineScope {
                        (0 until 8)
                            .map { w ->
                                async(Dispatchers.Default) {
                                    repeat(2_000) { i ->
                                        // Half are armed far off and disarmed at once; the rest are due within a few ms.
                                        if (i % 2 == 0) {
                                            timer.schedule(5.seconds) { fired.trySend(1) }.dispose()
                                        } else {
                                            timer.schedule(((i % 7) + 1).milliseconds) { fired.trySend(1) }
                                        }
                                    }
                                    w
                                }
                            }.forEach { it.await() }
                    }
                    var count = 0
                    withTimeout(20.seconds) {
                        while (count < 8 * 1_000) {
                            fired.receive()
                            count++
                        }
                    }
                    delay(100.milliseconds)
                    var extra = 0
                    while (fired.tryReceive().isSuccess) extra++
                    assertEquals(0, extra, "a disarmed deadline fired")
                } finally {
                    timer.close()
                }
            }
        }

    @Test
    fun anExpiredStreamDeadlineCarriesTheBoundThatExpired() =
        runTest {
            real {
                val failure = assertFailsWith<TimeoutCancellationException> { withStreamDeadline(40.milliseconds) { awaitCancellation() } }
                val bound = failure.suppressedExceptions.filterIsInstance<StreamDeadlineBound>().single()
                assertEquals(40.milliseconds, bound.bound)
            }
        }

    @Test
    fun anOuterDeadlineThatFiresFirstIsReportedAsTheOuterBoundNotTheInnerOne() =
        runTest {
            real {
                val failure =
                    assertFailsWith<TimeoutCancellationException> {
                        withStreamDeadline(60.milliseconds) {
                            // The inner bound is far off; the outer one expires while the inner is armed.
                            withStreamDeadline(30.seconds) { awaitCancellation() }
                        }
                    }
                val bound = failure.suppressedExceptions.filterIsInstance<StreamDeadlineBound>().single()
                assertEquals(60.milliseconds, bound.bound, "the outer bound expired, not the inner")
            }
        }

    @Test
    fun anInnerDeadlineThatFiresFirstIsCaughtByTheOuterCallerAsTheInnerBoundAndTheOuterOneKeepsRunning() =
        runTest {
            real {
                val result =
                    withStreamDeadline(30.seconds) {
                        val inner =
                            try {
                                withStreamDeadline(40.milliseconds) { awaitCancellation() }
                                null
                            } catch (e: TimeoutCancellationException) {
                                e.suppressedExceptions
                                    .filterIsInstance<StreamDeadlineBound>()
                                    .single()
                                    .bound
                            }
                        inner to "outer still running"
                    }
                assertEquals(40.milliseconds to "outer still running", result)
            }
        }

    @Test
    fun aCancelledCallerIsNotReportedAsADeadline() =
        runTest {
            real {
                coroutineScope {
                    val job = launch(Dispatchers.Default) { withStreamDeadline(30.seconds) { awaitCancellation() } }
                    delay(30.milliseconds)
                    job.cancel()
                    job.join()
                    assertTrue(job.isCancelled)
                }
            }
        }

    @Test
    fun aDeadlineThatFiresAsTheBlockReturnsGivesEitherTheValueOrTheTimeoutNeverAnythingElse() =
        runTest {
            real {
                var value = 0
                var timedOut = 0
                repeat(300) {
                    try {
                        // The block does not suspend and spends about the bound, so the expiry lands at the end of it.
                        val got =
                            withStreamDeadline(1.milliseconds) {
                                val end =
                                    kotlin.time.TimeSource.Monotonic
                                        .markNow() + 1.milliseconds
                                while (end.hasNotPassedNow()) Unit
                                42
                            }
                        assertEquals(42, got)
                        value++
                    } catch (e: TimeoutCancellationException) {
                        timedOut++
                    }
                }
                assertEquals(300, value + timedOut)
            }
        }

    @Test
    fun anIdleTimerMakesNoPassesAndOneFarDeadlineDoesNotWakeIt() =
        runTest {
            real {
                val timer = NativeDeadlineTimer()
                try {
                    delay(200.milliseconds)
                    val settled = timer.passes.value
                    delay(1.seconds)
                    assertEquals(settled, timer.passes.value, "an idle timer thread woke")
                    val far = timer.schedule(1.hours) { error("an hour has not passed") }
                    delay(200.milliseconds)
                    val armed = timer.passes.value
                    delay(1.seconds)
                    assertEquals(armed, timer.passes.value, "a timer with one far deadline woke before it was due")
                    far.dispose()
                } finally {
                    timer.close()
                }
            }
        }
}
