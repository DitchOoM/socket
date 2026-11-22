package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.QuicSessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The handler pool on its own threads: lazy start and stop, connection-serial steps, a blocked handler's reach. */
class HandlerPoolThreadTests {
    private fun threadsNamed(prefix: String): List<Thread> = Thread.getAllStackTraces().keys.filter { it.name.startsWith("$prefix-") }

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            assertTrue(System.nanoTime() < until, what)
            Thread.sleep(5)
        }
    }

    @Test
    fun noThreadStartsBeforeTheFirstUseAndTheThreadsEndOnceTheStoppedPoolHasNoUse() {
        val name = "pool-life-${System.nanoTime()}"
        val pool = HandlerPool(HandlerThreads.Own(2), name, ::testContained)
        val outbox = HandlerOutbox()
        pool.lane(outbox, QuicSessionId("unused"))
        assertEquals(0, threadsNamed(name).size, "a pool nobody entered starts no thread")
        assertEquals(Entered.Entered, pool.enter())
        waitFor("both threads start, off this thread") { threadsNamed(name).size == 2 && pool.liveThreads() == 2 }
        pool.stop()
        assertIs<PoolLife.Stopping>(pool.life())
        assertEquals(2, threadsNamed(name).size, "a use still running keeps the threads")
        pool.leave()
        assertIs<PoolLife.Stopped>(pool.life())
        waitFor("both threads end") { threadsNamed(name).isEmpty() && pool.liveThreads() == 0 }
    }

    @Test
    fun twoStepsOfOneConnectionNeverOverlapWhateverThreadResumesThem() =
        runBlocking {
            val pool = HandlerPool(HandlerThreads.Own(HandlerPool.SERVER_THREADS), "pool-serial", ::testContained)
            val outbox = HandlerOutbox()
            val overlaps = AtomicInteger()
            val lanes =
                List(CONNECTIONS) {
                    pool.enter()
                    pool.lane(outbox, QuicSessionId("c$it"))
                }
            val inside = List(CONNECTIONS) { AtomicInteger() }
            val work =
                lanes.mapIndexed { i, lane ->
                    launch {
                        withContext(lane) {
                            List(STREAMS) {
                                launch {
                                    repeat(STEPS) {
                                        if (inside[i].incrementAndGet() != 1) overlaps.incrementAndGet()
                                        Thread.onSpinWait()
                                        inside[i].decrementAndGet()
                                        // Every other step resumes from another thread, the others from the holder.
                                        if (it % 2 == 0) yield() else withContext(Dispatchers.Default) { }
                                    }
                                }
                            }.joinAll()
                        }
                    }
                }
            work.joinAll()
            assertEquals(0, overlaps.get(), "steps of one connection ran at the same time")
            repeat(CONNECTIONS) { pool.leave() }
            pool.stop()
        }

    /**
     * The loop's side against four real workers: a loop thread makes passes that queue steps of 16 connections, while
     * another thread queues steps of the same connections from outside any pass. Every step runs once, and no two steps
     * of one connection overlap.
     */
    @Test
    fun stepsALoopQueuesInItsPassesRunOnceAndSeriallyAgainstRealWorkers() {
        val pool = HandlerPool(HandlerThreads.Own(HandlerPool.SERVER_THREADS), "pool-loop", ::testContained)
        val outbox = HandlerOutbox()
        val lanes =
            List(LOOP_LANES) {
                pool.enter()
                pool.lane(outbox, QuicSessionId("c$it"))
            }
        val inside = List(LOOP_LANES) { AtomicInteger() }
        val overlaps = AtomicInteger()
        val ran = AtomicLong()

        fun stepOf(i: Int) =
            Runnable {
                if (inside[i].incrementAndGet() != 1) overlaps.incrementAndGet()
                Thread.onSpinWait()
                inside[i].decrementAndGet()
                ran.incrementAndGet()
            }
        val loop =
            Thread {
                repeat(PASSES) {
                    outbox.begin()
                    for (i in lanes.indices) repeat(PER_PASS) { lanes[i].dispatch(EmptyCoroutineContext, stepOf(i)) }
                    outbox.end()
                }
            }
        val other = Thread { repeat(PASSES) { for (i in lanes.indices) lanes[i].dispatch(EmptyCoroutineContext, stepOf(i)) } }
        loop.start()
        other.start()
        loop.join()
        other.join()
        val expected = PASSES.toLong() * LOOP_LANES * (PER_PASS + 1)
        waitFor("every queued step runs: ${ran.get()} of $expected") { ran.get() == expected }
        assertEquals(0, overlaps.get(), "steps of one connection ran at the same time")
        lanes.forEach { assertIs<LaneClaim.Idle>(it.claimNow()) }
        repeat(LOOP_LANES) { pool.leave() }
        pool.stop()
    }

    @Test
    fun aBlockedHandlerHoldsUpOnlyItsOwnConnection() =
        runBlocking {
            val pool = HandlerPool(HandlerThreads.Own(HandlerPool.SERVER_THREADS), "pool-blocked-one", ::testContained)
            val baseline = ticksPerConnection(pool, blocked = 0)
            val withOne = ticksPerConnection(pool, blocked = 1)
            val floor = baseline.average() / 2
            val slowest = withOne.drop(1).min()
            println("[handler pool] ticks per connection: baseline mean %.0f, one blocked: slowest %d".format(baseline.average(), slowest))
            assertEquals(0L, withOne[0], "the blocked connection ran no step")
            assertTrue(slowest >= floor, "a connection behind no blocked handler slowed to $slowest ticks (baseline ${baseline.average()})")
            pool.stop()
        }

    @Test
    fun asManyBlockedHandlersAsThreadsStopEveryHandlerAndTheOthersQueueOneStepPerCoroutine() =
        runBlocking {
            val pool = HandlerPool(HandlerThreads.Own(HandlerPool.SERVER_THREADS), "pool-blocked-all", ::testContained)
            val outbox = HandlerOutbox()
            val release = CountDownLatch(1)
            val blocked = CountDownLatch(HandlerPool.SERVER_THREADS)
            val lanes = List(CONNECTIONS) { pool.enter().let { _ -> pool.lane(outbox, QuicSessionId("c$it")) } }
            val ticks = List(CONNECTIONS) { AtomicLong() }
            val jobs = ArrayList<Job>()
            for (i in 0 until HandlerPool.SERVER_THREADS) {
                jobs +=
                    launch(lanes[i]) {
                        blocked.countDown()
                        release.await()
                    }
            }
            assertTrue(blocked.await(10, TimeUnit.SECONDS), "every thread holds a blocked handler")
            for (i in HandlerPool.SERVER_THREADS until CONNECTIONS) jobs += ticker(lanes[i], ticks[i])
            Thread.sleep(WINDOW_MS)
            // The documented limit: with every thread held, no handler of any connection runs.
            assertEquals(0L, ticks.sumOf { it.get() }, "a handler ran while every pool thread was held")
            for (i in HandlerPool.SERVER_THREADS until CONNECTIONS) {
                // Each connection's one coroutine was resumed from the timer thread, again and again: one step waits for it.
                assertTrue(lanes[i].pending() <= 1, "connection $i queued ${lanes[i].pending()} steps for its one coroutine")
            }
            release.countDown()
            waitFor("every connection runs again once the threads are free") {
                (HandlerPool.SERVER_THREADS until CONNECTIONS).all { ticks[it].get() > 0 }
            }
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            repeat(CONNECTIONS) { pool.leave() }
            pool.stop()
        }

    /** Ticks each of [CONNECTIONS] connections' one handler makes in [WINDOW_MS], each tick one timer resume; the first [blocked] block. */
    private suspend fun ticksPerConnection(
        pool: HandlerPool,
        blocked: Int,
    ): List<Long> =
        kotlinx.coroutines.coroutineScope {
            val outbox = HandlerOutbox()
            val release = CountDownLatch(1)
            val lanes = List(CONNECTIONS) { pool.enter().let { _ -> pool.lane(outbox, QuicSessionId("c$it")) } }
            val ticks = List(CONNECTIONS) { AtomicLong() }
            val jobs =
                lanes.mapIndexed { i, lane ->
                    if (i < blocked) launch(lane) { release.await() } else ticker(lane, ticks[i])
                }
            Thread.sleep(WINDOW_MS)
            val counted = ticks.map { it.get() }
            release.countDown()
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            repeat(CONNECTIONS) { pool.leave() }
            counted
        }

    private fun kotlinx.coroutines.CoroutineScope.ticker(
        lane: CoroutineContext,
        ticks: AtomicLong,
    ): Job =
        launch(lane) {
            while (true) {
                delay(1)
                ticks.incrementAndGet()
            }
        }

    private companion object {
        const val CONNECTIONS = 64
        const val STREAMS = 8
        const val STEPS = 200
        const val WINDOW_MS = 500L
        const val LOOP_LANES = 16
        const val PASSES = 2_000
        const val PER_PASS = 3
    }
}
