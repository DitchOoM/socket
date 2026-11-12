package com.ditchoom.socket.quic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The bounds a failure from [withStreamDeadline] carries, on it or on its causes (kotlinx's stack-trace recovery copies it, keeping the original as the cause). */
internal fun streamBoundsOf(failure: Throwable): List<Duration> =
    generateSequence(failure) { it.cause }
        .take(8)
        .flatMap { it.suppressed.asSequence() }
        .filterIsInstance<StreamDeadlineBound>()
        .map { it.bound }
        .toList()

/**
 * [DeadlineTimer] wakes its thread only for a deadline earlier than the one it sleeps towards, survives
 * what would kill a thread, and [withStreamDeadline] on it keeps what `withTimeout` gave a bounded stream
 * operation: the bound, the exception, nesting, cancellation.
 *
 * Wake-ups are counted through [DeadlineTimer.passes] on a timer of the test's own, so nothing else in
 * the process can add one. The count starts once the thread is parked ([awaitParked]) rather than after a
 * pause, so it does not depend on the machine's speed. A park may return without a wake-up ("spurious", which
 * `LockSupport` permits), so the assertions allow [SPURIOUS] extra passes: against the hundreds that a wake-up
 * per arming would make, two cannot hide one. [StreamDeadlineWakeupTests] counts the same property on the
 * real stream path.
 */
class DeadlineTimerWakeupTests {
    @Test
    fun deadlinesLaterThanTheOneItSleepsTowardsWakeNothing() {
        val timer = DeadlineTimer()
        val sleepingTowards = timer.schedule(60_000) { }
        awaitParked(timer)
        val settled = timer.passes.get()
        repeat(FEWER_THAN_A_SWEEP) { timer.schedule(61_000L + it) { }.dispose() }
        assertWoke(timer, settled, "$FEWER_THAN_A_SWEEP later deadlines, armed and disarmed")
        sleepingTowards.dispose()
    }

    @Test
    fun aDisarmedDeadlineLeavesTheThreadSleepingTowardsItAndTheNextLaterOneWakesNothing() {
        val timer = DeadlineTimer()
        timer.schedule(60_000) { }.dispose()
        awaitParked(timer)
        val settled = timer.passes.get()
        // The stale 60 s target is still what the thread sleeps towards; each of these is later than it.
        repeat(FEWER_THAN_A_SWEEP) { timer.schedule(61_000) { }.dispose() }
        assertWoke(timer, settled, "disarming, then $FEWER_THAN_A_SWEEP later armings")
    }

    @Test
    fun disarmedDeadlinesAreSweptSoTheQueueStaysBounded() {
        val timer = DeadlineTimer()
        val sleepingTowards = timer.schedule(60_000) { }
        val armed = 20 * DeadlineQueue.COMPACT_MIN
        repeat(armed) { timer.schedule(61_000L + it) { }.dispose() }
        // The sweep runs on the timer thread; it must leave far fewer than were armed.
        val limit = 2 * DeadlineQueue.COMPACT_MIN
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (timer.queued > limit && deadline.hasNotPassedNow()) Thread.sleep(10)
        assertTrue(timer.queued <= limit, "$armed disarmed deadlines left ${timer.queued} in the queue, more than $limit")
        sleepingTowards.dispose()
    }

    @Test
    fun anIdleTimerDoesNotWake() {
        val timer = DeadlineTimer()
        timer.start()
        awaitParked(timer)
        val settled = timer.passes.get()
        Thread.sleep(500)
        assertWoke(timer, settled, "half a second with nothing armed")
    }

    @Test
    fun anEarlierDeadlineWakesTheThreadAndFiresAtItsBound() {
        val timer = DeadlineTimer()
        timer.schedule(60_000) { }
        awaitParked(timer)
        val settled = timer.passes.get()
        val fired = CountDownLatch(1)
        val mark = TimeSource.Monotonic.markNow()
        timer.schedule(100) { fired.countDown() }
        assertTrue(fired.await(10, TimeUnit.SECONDS), "a deadline earlier than the one slept towards never fired")
        val elapsed = mark.elapsedNow()
        assertTrue(elapsed >= 100.milliseconds, "fired after $elapsed, before its 100 ms bound")
        assertTrue(timer.passes.get() > settled, "the earlier deadline did not make the thread pass over its queue")
    }

    @Test
    fun aDisarmedDeadlineNeverFires() {
        val timer = DeadlineTimer()
        val fired = AtomicBoolean(false)
        timer.schedule(50) { fired.set(true) }.dispose()
        Thread.sleep(300)
        assertFalse(fired.get(), "a disarmed deadline fired")
    }

    @Test
    fun deadlinesFireInOrderOfDueTimeWhateverTheOrderTheyWereArmedIn() {
        val timer = DeadlineTimer()
        val order = CopyOnWriteArrayList<Int>()
        val done = CountDownLatch(3)
        timer.schedule(300) { order.add(3).also { done.countDown() } }
        timer.schedule(100) { order.add(1).also { done.countDown() } }
        timer.schedule(200) { order.add(2).also { done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(1, 2, 3), order.toList())
    }

    @Test
    fun anActionThatThrowsIsReportedAndTheThreadKeepsFiringDeadlines() {
        val reported = CountDownLatch(1)
        val timer = DeadlineTimer(reportingThreads { reported.countDown() })
        val after = CountDownLatch(1)
        timer.schedule(10) { error("an action that throws") }
        timer.schedule(60) { after.countDown() }
        assertTrue(reported.await(10, TimeUnit.SECONDS), "the failure was swallowed")
        assertTrue(after.await(10, TimeUnit.SECONDS), "the thread stopped firing deadlines after an action threw")
    }

    @Test
    fun anErrorThatIsNotAnActionsAndAHandlerThatThrowsLeaveTheThreadFiringDeadlines() {
        // The clock fails on the timer thread twice, as a pass does when the heap cannot grow; the handler throws too.
        val failures = AtomicInteger(2)
        val handlerCalls = AtomicInteger(0)
        val timerThread = AtomicReference<Thread>()
        val timer =
            DeadlineTimer(
                threadFactory =
                    ThreadFactory { task ->
                        Thread(task, "quic-deadline-timer (test)").apply {
                            isDaemon = true
                            setUncaughtExceptionHandler { _, _ ->
                                handlerCalls.incrementAndGet()
                                throw IllegalStateException("a handler that throws")
                            }
                            timerThread.set(this)
                        }
                    },
                clock = {
                    if (Thread.currentThread() === timerThread.get() && failures.getAndDecrement() > 0) {
                        throw OutOfMemoryError("a pass that fails")
                    }
                    System.nanoTime()
                },
            )
        timer.start()
        val fired = CountDownLatch(1)
        timer.schedule(50) { fired.countDown() }
        assertTrue(fired.await(10, TimeUnit.SECONDS), "a thread that survived two failed passes did not fire a deadline")
        assertTrue(handlerCalls.get() >= 2, "the failed passes were not reported")
        assertTrue(timerThread.get().isAlive, "the timer thread died")
    }

    @Test
    fun aStrayInterruptIsIgnoredAndDoesNotSpinTheThread() {
        val timerThread = AtomicReference<Thread>()
        val timer =
            DeadlineTimer(
                ThreadFactory { task -> Thread(task, "quic-deadline-timer (test)").apply { isDaemon = true }.also(timerThread::set) },
            )
        val sleepingTowards = timer.schedule(60_000) { }
        awaitParked(timer)
        val settled = timer.passes.get()
        repeat(5) {
            timerThread.get().interrupt()
            Thread.sleep(20)
        }
        Thread.sleep(300)
        // Five interrupts are at most five passes; a loop that never cleared the flag would pass thousands of times.
        assertTrue(timer.passes.get() - settled <= 5 + SPURIOUS, "${timer.passes.get() - settled} passes after 5 interrupts")
        // An action that re-asserts an interrupt on the timer thread, as a cancellation handler may.
        val reasserted = CountDownLatch(1)
        timer.schedule(10) {
            Thread.currentThread().interrupt()
            reasserted.countDown()
        }
        assertTrue(reasserted.await(10, TimeUnit.SECONDS))
        awaitParked(timer)
        val afterwards = timer.passes.get()
        Thread.sleep(300)
        assertTrue(timer.passes.get() - afterwards <= SPURIOUS, "${timer.passes.get() - afterwards} passes with nothing due")
        val fired = CountDownLatch(1)
        timer.schedule(20) { fired.countDown() }
        assertTrue(fired.await(10, TimeUnit.SECONDS), "an interrupted thread stopped firing deadlines")
        sleepingTowards.dispose()
    }

    @Test
    fun aBoundThatDoesNotCompleteFailsAtItsBoundWithTimeoutCancellationException(): Unit =
        runBlocking {
            val mark = TimeSource.Monotonic.markNow()
            val failure = assertFailsWith<TimeoutCancellationException> { withStreamDeadline(150.milliseconds) { awaitCancellation() } }
            val elapsed = mark.elapsedNow()
            assertTrue(elapsed >= 150.milliseconds, "failed after $elapsed, before its 150 ms bound")
            assertTrue(elapsed < 5.seconds, "failed after $elapsed, a 150 ms bound")
            assertEquals(listOf(150.milliseconds), streamBoundsOf(failure))
        }

    @Test
    fun theInnerOfTwoNestedBoundsExpiresFirstAndTheCallerSeesTheInnerBound(): Unit =
        runBlocking {
            val failure =
                assertFailsWith<TimeoutCancellationException> {
                    withStreamDeadline(30.seconds) { withStreamDeadline(100.milliseconds) { awaitCancellation() } }
                }
            assertEquals(listOf(100.milliseconds), streamBoundsOf(failure))
        }

    @Test
    fun theOuterOfTwoNestedBoundsExpiresFirstAndTheCallerSeesTheOuterBound(): Unit =
        runBlocking {
            val failure =
                assertFailsWith<TimeoutCancellationException> {
                    withStreamDeadline(100.milliseconds) { withStreamDeadline(30.seconds) { awaitCancellation() } }
                }
            assertEquals(listOf(100.milliseconds), streamBoundsOf(failure))
        }

    @Test
    fun anEnclosingWithTimeoutStillWinsOverALongerStreamBoundAndIsNotReportedAsTheStreamBound(): Unit =
        runBlocking {
            val failure =
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(100.milliseconds) { withStreamDeadline(30.seconds) { awaitCancellation() } }
                }
            assertEquals(emptyList(), streamBoundsOf(failure), "the enclosing withTimeout's expiry was reported as the stream's bound")
        }

    @Test
    fun cancellingTheCallerWhileABoundIsArmedCancelsItWithoutATimeout(): Unit =
        runBlocking {
            val armed = CountDownLatch(1)
            val caller =
                async(Dispatchers.Default) {
                    withStreamDeadline(30.seconds) {
                        armed.countDown()
                        awaitCancellation()
                    }
                }
            assertTrue(armed.await(10, TimeUnit.SECONDS))
            caller.cancel()
            val failure = assertFailsWith<CancellationException> { caller.await() }
            assertFalse(failure is TimeoutCancellationException, "a cancelled caller reported a timeout: $failure")
        }

    @Test
    fun aBlockThatFailsFailsTheCallerWithItsOwnException(): Unit =
        runBlocking {
            val failure = assertFailsWith<IllegalStateException> { withStreamDeadline(30.seconds) { error("the block's own") } }
            assertEquals("the block's own", failure.message)
        }

    @Test
    fun aBoundOfZeroOrLessTimesOutWithoutRunningTheBlock(): Unit =
        runBlocking {
            val ran = AtomicBoolean(false)
            assertFailsWith<TimeoutCancellationException> { withStreamDeadline(0.milliseconds) { ran.set(true) } }
            assertFalse(ran.get())
        }

    @Test
    fun anInfiniteBoundIsNotArmed(): Unit =
        runBlocking {
            val value =
                withStreamDeadline(Duration.INFINITE) {
                    delay(20)
                    7
                }
            assertEquals(7, value)
        }

    private fun reportingThreads(onReport: () -> Unit) =
        ThreadFactory { task ->
            Thread(task, "quic-deadline-timer (test)").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, _ -> onReport() }
            }
        }

    private fun assertWoke(
        timer: DeadlineTimer,
        settled: Int,
        what: String,
    ) {
        val passes = timer.passes.get() - settled
        assertTrue(passes <= SPURIOUS, "$what made the timer thread pass over its queue $passes times; at most $SPURIOUS were expected")
    }

    private companion object {
        /** A park may return without a wake-up; two such passes are allowed, against hundreds if each arming woke the thread. */
        const val SPURIOUS = 2

        /** Disarmed deadlines are swept from [DeadlineQueue.COMPACT_MIN]; fewer never cause a wake-up. */
        const val FEWER_THAN_A_SWEEP = DeadlineQueue.COMPACT_MIN - 124
    }
}

/** Waits until the timer thread is parked, so that a count of its passes starts from rest; bounded so a thread that never parks fails the test. */
internal fun awaitParked(timer: DeadlineTimer) {
    val deadline = TimeSource.Monotonic.markNow() + 10.seconds
    while (!timer.isParked) {
        check(deadline.hasNotPassedNow()) { "the timer thread never parked" }
        Thread.sleep(1)
    }
}
