package com.ditchoom.socket.quic.core.loop

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import kotlinx.coroutines.Runnable
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Deadlines armed from threads that are not the loop's, while the loop sleeps and wakes: none is lost and none is
 * late. A lost wake shows as a deadline that never fires, because the loop sleeps without a deadline of its own.
 */
class LoopDeadlineWakeTests {
    @Test
    fun deadlinesArmedFromOtherThreadsAllFireOnTime() {
        val limits = BatchLimits(maxDatagrams = 4, maxDatagramSize = 1500)
        val channel = DatagramChannel.open().bind(InetSocketAddress("127.0.0.1", 0))
        channel.configureBlocking(false)
        val send = SendBatch(BufferFactory.Default.allocate(limits.maxDatagrams * limits.maxDatagramSize), address = 0L, limits)
        val receive =
            ReceiveBatch(
                FixedPool(limits.maxDatagrams) { ReceiveBuffer(it, BufferFactory.Default.allocate(limits.maxDatagramSize), address = 0L) },
                limits,
            )
        val clock = LoopClock { System.nanoTime() }
        val loop = Loop(SelectorWait(channel, clock), clock, NioOneByOne(channel, send), NoParts, receive, send)
        startLoop(loop, "core-deadline-wake")

        val armers = 4
        val perArmer = 400
        val fired = CountDownLatch(armers * perArmer)
        val worstLateNanos = AtomicLong()
        val threads =
            List(armers) { armer ->
                Thread {
                    val random = Random(armer)
                    repeat(perArmer) {
                        val due = System.nanoTime() + random.nextLong(0, 3_000_000)
                        loop.armDeadline(
                            due,
                            Runnable {
                                worstLateNanos.accumulateAndGet(System.nanoTime() - due, ::maxOf)
                                fired.countDown()
                            },
                        )
                        // Long enough for the loop to go back to sleep between some of the arms.
                        if (random.nextInt(4) == 0) Thread.sleep(random.nextLong(1, 6))
                    }
                }.also(Thread::start)
            }
        threads.forEach { it.join() }
        val allFired = fired.await(10, TimeUnit.SECONDS)
        val unfired = fired.count
        val closed = CompletableFuture<Unit>()
        loop.close { closed.complete(Unit) }
        closed.get(5, TimeUnit.SECONDS)
        println(
            "[loop deadline wake] ${armers * perArmer} deadlines, worst lateness ${worstLateNanos.get() / 1_000} µs, loop wakes by others ${loop.callerCounts.loopWakes}",
        )
        assertTrue(allFired, "$unfired of ${armers * perArmer} deadlines never fired: a wake was lost")
        assertTrue(worstLateNanos.get() < 250_000_000L, "a deadline fired ${worstLateNanos.get() / 1_000_000} ms late")
    }

    private object NoParts : LoopParts {
        override fun onDatagram(
            buffer: ReceiveBuffer,
            length: Int,
            from: PathKey,
            to: ArrivedOn,
        ) = Unit

        override fun collect(out: SendBatch): Collected = Collected.Everything

        override fun loopFailed(cause: Throwable) = Unit
    }
}
