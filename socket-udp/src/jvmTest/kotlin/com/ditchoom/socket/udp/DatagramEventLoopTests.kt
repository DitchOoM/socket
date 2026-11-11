@file:OptIn(InternalDatagramLoopApi::class, ExperimentalDatagramApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [DatagramEventLoop] and [DatagramEventLoopGroup]: what runs where, the timers, waiting for socket
 * readiness in the loop's own selector, and the thread's life (started when needed, ended when idle,
 * refused with a typed failure when no thread can be had).
 */
class DatagramEventLoopTests {
    private val groups = mutableListOf<DatagramEventLoopGroup>()

    private fun group(
        size: Int = 1,
        linger: kotlin.time.Duration = 50.milliseconds,
        threads: ThreadFactory? = null,
    ): DatagramEventLoopGroup = DatagramEventLoopGroup(size, "loop-test-${groups.size}", linger, threads).also { groups.add(it) }

    private fun loop(linger: kotlin.time.Duration = 50.milliseconds): DatagramEventLoop = group(linger = linger).loops.single()

    @AfterTest
    fun everyLoopThreadEnds() {
        // Every test leaves its loops unheld; their threads must end on their own.
        for (g in groups) awaitNoThreads(g)
    }

    private fun awaitNoThreads(g: DatagramEventLoopGroup) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (g.runningThreads().isEmpty() && liveThreadsNamed(g).isEmpty()) return
            Thread.sleep(10)
        }
        throw AssertionError("loop threads still alive 5 s after their last use: ${liveThreadsNamed(g)}")
    }

    private fun liveThreadsNamed(g: DatagramEventLoopGroup): List<String> {
        val prefix =
            g.loops
                .first()
                .toString()
                .substringBeforeLast('-')
        return Thread
            .getAllStackTraces()
            .keys
            .filter { it.isAlive && it.name.startsWith("$prefix-") }
            .map { it.name }
    }

    @Test
    fun aDispatchedCoroutineRunsOnTheLoopThreadInOrder() =
        runBlocking {
            val loop = loop()
            val seen = Collections.synchronizedList(mutableListOf<Int>())
            val threads = Collections.synchronizedSet(mutableSetOf<Thread>())
            withContext(loop) {
                repeat(100) { i ->
                    launch {
                        threads.add(Thread.currentThread())
                        assertTrue(loop.isLoopThread())
                        seen.add(i)
                    }
                }
            }
            assertEquals((0 until 100).toList(), seen)
            assertEquals(1, threads.size, "a loop is one thread")
            assertTrue(threads.single().isDaemon, "a loop thread must never keep the process alive")
        }

    @Test
    fun dispatchesFromManyThreadsAreEachRunExactlyOnce() {
        val loop = loop()
        val ran = AtomicInteger()
        val perThread = 20_000
        val senders = 8
        val done = CountDownLatch(senders * perThread)
        val threads =
            List(senders) {
                thread {
                    repeat(perThread) {
                        loop.dispatch(
                            kotlin.coroutines.EmptyCoroutineContext,
                            Runnable {
                                ran.incrementAndGet()
                                done.countDown()
                            },
                        )
                    }
                }
            }
        threads.forEach { it.join() }
        assertTrue(done.await(30, TimeUnit.SECONDS), "only ${ran.get()} of ${senders * perThread} tasks ran")
        assertEquals(senders * perThread, ran.get())
    }

    @Test
    fun timersFireInDeadlineOrderAndADisposedOneNever() =
        runBlocking {
            val loop = loop()
            val fired = Collections.synchronizedList(mutableListOf<String>())
            withContext(loop) {
                val a =
                    launch {
                        delay(60)
                        fired.add("60")
                    }
                launch {
                    delay(20)
                    fired.add("20")
                }
                launch {
                    delay(40)
                    fired.add("40")
                }
                val cancelled =
                    launch {
                        delay(30)
                        fired.add("cancelled")
                    }
                cancelled.cancel()
                a.join()
            }
            assertEquals(listOf("20", "40", "60"), fired)
        }

    @Test
    fun withTimeoutAndSelectOnTimeoutFireOnTheLoop() =
        runBlocking {
            val loop = loop()
            withContext(loop) {
                val never = CompletableDeferred<Unit>()
                assertNull(withTimeoutOrNull(30.milliseconds) { never.await() })
                val picked =
                    select<String> {
                        never.onAwait { "never" }
                        onTimeout(20.milliseconds) { if (loop.isLoopThread()) "timeout on the loop" else "timeout elsewhere" }
                    }
                assertEquals("timeout on the loop", picked)
            }
        }

    @Test
    fun aDelayArmedFromAnotherThreadFires() {
        val loop = loop()
        val fired = CountDownLatch(1)
        // invokeOnTimeout from a thread that is not the loop: armed through a task.
        loop.invokeOnTimeout(20, Runnable { fired.countDown() }, kotlin.coroutines.EmptyCoroutineContext)
        assertTrue(fired.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun theThreadEndsWhenIdleAndUnheldAndANewOneServesTheNextDispatch() =
        runBlocking {
            val loop = loop(linger = 30.milliseconds)
            val first = withContext(loop) { Thread.currentThread() }
            first.join(5_000)
            assertTrue(!first.isAlive, "an idle, unheld loop's thread must end after its linger")
            assertNull(loop.runningThread)
            val second = withContext(loop) { Thread.currentThread() }
            assertTrue(second !== first, "the next dispatch must start a new thread")
        }

    @Test
    fun aHeldLoopKeepsItsThreadUntilReleased() =
        runBlocking {
            val loop = loop(linger = 20.milliseconds)
            val hold = loop.hold()
            val thread = assertNotNull(loop.runningThread, "holding a loop starts its thread")
            Thread.sleep(200)
            assertTrue(thread.isAlive, "a held loop's thread must outlive its linger")
            hold.release()
            hold.release() // idempotent
            thread.join(5_000)
            assertTrue(!thread.isAlive, "released and idle, the thread must end")
        }

    @Test
    fun aGroupSpreadsHoldsAndPrefersANearLoopWhileItIsNotBusier() {
        val g = group(size = 3)
        val a = g.hold()
        val b = g.hold()
        val c = g.hold()
        assertEquals(3, setOf(a.loop, b.loop, c.loop).size, "three holds on three idle loops spread")
        // Every loop has one holder: near is taken (1 <= 1 + 1).
        val near = g.hold(near = a.loop)
        assertSame(a.loop, near.loop)
        // a.loop now has two, the others one: still within one of the least held.
        val near2 = g.hold(near = a.loop)
        assertSame(a.loop, near2.loop)
        // a.loop has three, the least held one: too busy, placed elsewhere.
        val far = g.hold(near = a.loop)
        assertTrue(far.loop !== a.loop)
        listOf(a, b, c, near, near2, far).forEach { it.release() }
    }

    @Test
    fun aLoopWhoseThreadCannotStartRefusesAHoldWithATypedFailureAndCanStartLater() {
        val attempts = AtomicInteger()
        val failFirst =
            ThreadFactory { task ->
                if (attempts.incrementAndGet() == 1) throw OutOfMemoryError("unable to create native thread: possibly out of memory")
                Thread(task, "loop-test-unstartable-0").apply { isDaemon = true }
            }
        val g = group(threads = failFirst)
        val refused = assertFailsWith<DatagramEventLoopUnavailableException> { g.hold() }
        assertIs<OutOfMemoryError>(refused.cause)
        assertNull(g.loops.single().runningThread, "a failed start leaves no thread recorded")
        val hold = g.hold()
        assertNotNull(g.loops.single().runningThread)
        hold.release()
    }

    @Test
    fun aDispatchThatCannotStartAThreadCancelsItsCoroutineAndRunsOnIo() {
        val never = ThreadFactory { throw OutOfMemoryError("unable to create native thread") }
        val loop = group(threads = never).loops.single()
        val job = Job()
        val ranOn = CompletableDeferred<Thread>()
        loop.dispatch(job, Runnable { ranOn.complete(Thread.currentThread()) })
        val thread = runBlocking { withTimeout(5.seconds) { ranOn.await() } }
        assertTrue(job.isCancelled, "the coroutine whose dispatch found no thread must be cancelled")
        assertTrue(thread.name.startsWith("DefaultDispatcher-worker"), "the queued work must still run, on IO: ran on ${thread.name}")
    }

    @Test
    fun aReceiveOnTheLoopWaitsInItsSelectorAndAnotherThreadIsNeverUsed() =
        runBlocking {
            val loop = loop()
            val receiver = UdpSocket.bind("127.0.0.1", 0)
            val sender = UdpSocket.bind("127.0.0.1", 0)
            try {
                val got =
                    async(loop) {
                        val r = receiver.receive()
                        Thread.currentThread() to r
                    }
                // Let the receive park first, so the datagram must be delivered by readiness.
                delay(50)
                sender.send(BufferFactory.Default.wrap("hello".encodeToByteArray()), receiver.localAddress)
                val (thread, result) = withTimeout(5.seconds) { got.await() }
                assertTrue(thread.name.startsWith("loop-test-"), "the receive resumed on ${thread.name}")
                val datagram = assertIs<DatagramReadResult.Received>(result).datagram
                assertEquals("hello", datagram.payload.readByteArray(datagram.payload.remaining()).decodeToString())
                datagram.payload.freeNativeMemory()
            } finally {
                receiver.close()
                sender.close()
            }
        }

    @Test
    fun aHundredChannelsWaitOnOneLoopThread() =
        runBlocking {
            val loop = loop()
            val receivers = List(100) { UdpSocket.bind("127.0.0.1", 0) }
            val sender = UdpSocket.bind("127.0.0.1", 0)
            try {
                val received =
                    receivers.mapIndexed { i, r ->
                        async(loop) {
                            val result = r.receive()
                            val d = assertIs<DatagramReadResult.Received>(result).datagram
                            d.payload
                                .readByteArray(d.payload.remaining())
                                .decodeToString()
                                .also { d.payload.freeNativeMemory() } to i
                        }
                    }
                delay(50)
                receivers.forEachIndexed { i, r -> sender.send(BufferFactory.Default.wrap("to-$i".encodeToByteArray()), r.localAddress) }
                val all = withTimeout(10.seconds) { received.awaitAll() }
                all.forEach { (text, i) -> assertEquals("to-$i", text) }
            } finally {
                receivers.forEach { it.close() }
                sender.close()
            }
        }

    @Test
    fun closingAChannelWakesAReceiveWaitingOnTheLoop() =
        runBlocking {
            val loop = loop()
            val receiver = UdpSocket.bind("127.0.0.1", 0)
            val parked = async(loop) { receiver.receive() }
            delay(50)
            receiver.close()
            assertIs<DatagramReadResult.Closed>(withTimeout(5.seconds) { parked.await() })
            Unit
        }

    @Test
    fun aCancelledReceiveOnTheLoopLeavesTheSocketOpenAndUsable() =
        runBlocking {
            val loop = loop()
            val receiver = UdpSocket.bind("127.0.0.1", 0)
            val sender = UdpSocket.bind("127.0.0.1", 0)
            try {
                val parked = launch(loop) { receiver.receive() }
                delay(50)
                parked.cancel()
                parked.join()
                assertTrue(receiver.isOpen)
                sender.send(BufferFactory.Default.wrap("after".encodeToByteArray()), receiver.localAddress)
                val r = withTimeout(5.seconds) { withContext(loop) { receiver.receive() } }
                val d = assertIs<DatagramReadResult.Received>(r).datagram
                assertEquals("after", d.payload.readByteArray(d.payload.remaining()).decodeToString())
                d.payload.freeNativeMemory()
            } finally {
                receiver.close()
                sender.close()
            }
        }

    // The UDP send/receive contracts, re-run with every channel operation on a loop.

    @Test
    fun onALoopSendNeverSilentlyDrops() =
        runBlocking(loop()) {
            withTimeout(60_000) { assertSendNeverSilentlyDrops(this, hostLoopback) }
        }

    @Test
    fun onALoopCloseWithParkedReceiveYieldsClosed() =
        runBlocking(loop()) {
            withTimeout(60_000) { assertCloseWithParkedReceiveYieldsClosed(this) }
        }

    @Test
    fun onALoopAnOversizedSendReportsTooLarge() =
        runBlocking(loop()) {
            withTimeout(60_000) { assertOversizedSendReportsTooLarge(this) }
        }

    @Test
    fun yieldOnTheLoopLetsOtherCoroutinesRun() =
        runBlocking {
            val loop = loop()
            val order = Collections.synchronizedList(mutableListOf<String>())
            withContext(loop) {
                val other = launch { order.add("other") }
                order.add("before yield")
                yield()
                order.add("after yield")
                other.join()
            }
            assertEquals(listOf("before yield", "other", "after yield"), order)
        }
}
