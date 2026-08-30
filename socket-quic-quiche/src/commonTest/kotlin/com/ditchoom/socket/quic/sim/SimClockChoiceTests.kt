@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic.sim

import com.ditchoom.socket.quic.DriverTime
import com.ditchoom.socket.quic.RealDriverClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * [SimClockChoice.resolve] against the two kinds of dispatcher, including the trap the resolver is
 * built around: `withContext(Dispatchers.Default)` *inside* `runTest`, where the scheduler element is
 * still in the context but every `delay()` is wall time. No quiche anywhere — this is the pure-Kotlin
 * half of the #497 guard; the sims' own construction-time tests live beside them in jvmTest.
 */
class SimClockChoiceTests {
    @Test
    fun underRunTest_theDefaultIsTheCallingSchedulersClock() =
        runTest {
            val clock = SimClockChoice.OfCallingDispatcher.resolve()
            assertIs<SimClock>(clock, "the default under runTest must be the scheduler's clock (#497)")

            // And THIS scheduler, not some other TestScope's: a mark it hands out advances with the
            // test's own time, and the instant it pushes into quiche is that same time.
            val mark = clock.markNow()
            testScheduler.advanceTimeBy(1234.milliseconds)
            assertEquals(1234.milliseconds, mark.elapsedNow(), "markNow() reads the calling scheduler")
            assertEquals(
                DriverTime.Virtual(testScheduler.currentTime * 1_000_000L),
                clock.quicheTime(),
                "quicheTime() pins libquiche to the calling scheduler's now",
            )
        }

    @Test
    fun underRunTest_virtualIsTheCallingSchedulersClock() =
        runTest {
            val clock = SimClockChoice.Virtual.resolve()
            assertIs<SimClock>(clock)
            val mark = clock.markNow()
            testScheduler.advanceTimeBy(50.milliseconds)
            assertEquals(50.milliseconds, mark.elapsedNow())
        }

    @Test
    fun underRunTest_wallIsAConstructionError() =
        runTest {
            val failure = assertFailsWith<IncoherentSimClockException> { SimClockChoice.Wall.resolve() }
            assertTrue("#497" in failure.message.orEmpty(), "the error names the defect it prevents: ${failure.message}")
        }

    /**
     * The trap: the `TestCoroutineScheduler` *element* is still in the context here, but the
     * interceptor is `Dispatchers.Default`, so every `delay()` underneath is wall time. A resolver keyed
     * on the element would hand back a [SimClock] nobody advances — #497 with the sides swapped.
     */
    @Test
    fun onARealDispatcherInsideRunTest_theDefaultIsTheWallClock() =
        runTest {
            withContext(Dispatchers.Default) {
                assertSame(RealDriverClock, SimClockChoice.OfCallingDispatcher.resolve())
            }
        }

    @Test
    fun onARealDispatcher_wallIsTheWallClock() =
        runTest {
            withContext(Dispatchers.Default) {
                assertSame(RealDriverClock, SimClockChoice.Wall.resolve())
            }
        }

    @Test
    fun onARealDispatcher_virtualIsAConstructionError() =
        runTest {
            withContext(Dispatchers.Default) {
                assertFailsWith<IncoherentSimClockException> { SimClockChoice.Virtual.resolve() }
            }
        }
}
