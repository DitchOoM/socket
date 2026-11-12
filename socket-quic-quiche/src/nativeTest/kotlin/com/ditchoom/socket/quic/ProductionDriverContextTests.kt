package com.ditchoom.socket.quic

import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Where a native driver's timers are armed. A change to the placement (a lane, a different dispatcher) that
 * quietly sent them back to kotlinx's `DefaultExecutor` would put a lock and a tree insert on every driver
 * turn again; these fail then.
 */
@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class ProductionDriverContextTests {
    private suspend fun <T> real(block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }

    /** Arms deadlines in [context] until the shared timer has taken one off its inbox; false if it never does. */
    private suspend fun armsOnTheSharedTimer(context: kotlin.coroutines.CoroutineContext): Boolean =
        real {
            val before = NativeDeadlineTimer.Shared.drainedTotal
            withContext(context) {
                assertTrue(withTimeoutOrNull(30.milliseconds) { awaitCancellation() } == null)
                delay(10.milliseconds)
            }
            val end = TimeSource.Monotonic.markNow() + 5.seconds
            while (NativeDeadlineTimer.Shared.drainedTotal == before && end.hasNotPassedNow()) delay(5.milliseconds)
            NativeDeadlineTimer.Shared.drainedTotal > before
        }

    @Test
    fun theProductionContextIsADelayThatArmsOnTheSharedTimer() =
        runTest {
            assertTrue(productionDriverContext[ContinuationInterceptor] is Delay)
            assertTrue(armsOnTheSharedTimer(productionDriverContext), "production context timers did not reach the shared timer")
        }

    @Test
    fun theLaneAPlacedDriverRunsOnArmsOnTheSharedTimerToo() =
        runTest {
            val placed = placeDriver(productionDriverContext)
            assertTrue(placed.context[ContinuationInterceptor] is Delay)
            assertTrue(armsOnTheSharedTimer(placed.context), "the driver's lane did not arm its timers on the shared timer")
        }

    @Test
    fun theControlHoldsPlainDefaultDoesNotArmOnTheSharedTimer() =
        runTest {
            // Proves the probe above can fail: a context without the timed dispatcher leaves the shared timer's count alone.
            assertTrue(!armsOnTheSharedTimer(Dispatchers.Default), "the control reached the shared timer")
        }
}
