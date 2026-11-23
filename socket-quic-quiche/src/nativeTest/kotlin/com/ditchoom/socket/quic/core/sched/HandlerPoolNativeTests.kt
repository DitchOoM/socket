@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.currentThreadId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The pool on Kotlin/Native workers: handlers run off the caller's thread, serially per connection, and the workers end. */
class HandlerPoolNativeTests {
    @Test
    fun aConnectionsHandlersRunOnPoolWorkersOneStepAtATimeAndThePoolStops() =
        runBlocking {
            val pool = HandlerPool(HandlerThreads.Own(HandlerPool.CLIENT_THREADS), "native-pool", ::testContained)
            val outbox = HandlerOutbox()
            val caller = currentThreadId()
            val lanes =
                List(CONNECTIONS) {
                    pool.enter()
                    pool.lane(outbox, QuicSessionId("c$it"))
                }
            val overlaps = AtomicInt(0)
            val onCaller = AtomicInt(0)
            lanes
                .map { lane ->
                    val inside = AtomicInt(0)
                    launch {
                        withContext(lane) {
                            List(STREAMS) {
                                launch {
                                    repeat(STEPS) {
                                        if (inside.incrementAndFetch() != 1) overlaps.incrementAndFetch()
                                        if (currentThreadId() == caller) onCaller.incrementAndFetch()
                                        inside.decrementAndFetch()
                                        if (it % 2 == 0) yield() else withContext(Dispatchers.Default) { }
                                    }
                                }
                            }.joinAll()
                        }
                    }
                }.joinAll()
            assertEquals(0, overlaps.load(), "steps of one connection ran at the same time")
            assertEquals(0, onCaller.load(), "a handler step ran on the caller's thread")
            assertNotEquals(0L, caller)
            pool.stop()
            assertIs<PoolLife.Stopping>(pool.life())
            repeat(CONNECTIONS) { pool.leave() }
            assertIs<PoolLife.Stopped>(pool.life())
            // Its workers end with it: the K/N workers terminate, which a thread that kept running would not.
            val until = TimeSource.Monotonic.markNow() + 10.seconds
            while (pool.liveThreads() > 0) {
                assertTrue(until.hasNotPassedNow(), "${pool.liveThreads()} pool workers still run after the stop")
                delay(5)
            }
        }

    private companion object {
        const val CONNECTIONS = 8
        const val STREAMS = 4
        const val STEPS = 100
    }
}
