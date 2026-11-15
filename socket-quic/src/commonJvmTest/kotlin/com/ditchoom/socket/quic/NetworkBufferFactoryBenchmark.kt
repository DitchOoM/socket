package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import java.lang.management.ManagementFactory
import java.util.concurrent.ArrayBlockingQueue
import kotlin.test.Test

/**
 * Allocate on one thread and free on another, over a mix of 64 B to 64 KiB, for each buffer factory. Prints ns/op and
 * the heap bytes each op allocates on the two threads. Runs only when `NETWORK_FACTORY_BENCH` is set to an op count.
 */
class NetworkBufferFactoryBenchmark {
    @Test
    fun allocateOnOneThreadFreeOnAnother() {
        val ops = System.getenv("NETWORK_FACTORY_BENCH")?.toIntOrNull() ?: return
        println("[buffer factory bench] $ops ops, sizes ${SIZES.joinToString()}")
        repeat(2) { round ->
            for ((name, factory) in factories()) {
                run(factory, minOf(ops, 100_000))
                val result = run(factory, ops)
                println("[buffer factory bench] round $round %-14s %8.1f ns/op %8.1f heap B/op".format(name, result.first, result.second))
            }
        }
    }

    private fun factories(): List<Pair<String, BufferFactory>> =
        listOf(
            "Default" to BufferFactory.Default,
            "deterministic" to BufferFactory.deterministic(),
            "network" to BufferFactory.network(),
        )

    /** Returns ns/op and heap bytes/op summed over both threads. */
    private fun run(
        factory: BufferFactory,
        ops: Int,
    ): Pair<Double, Double> {
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val queue = ArrayBlockingQueue<PlatformBuffer>(256)
        var consumerBytes = 0L
        val consumer =
            Thread {
                val id = Thread.currentThread().id
                val before = mx.getThreadAllocatedBytes(id)
                repeat(ops) { queue.take().freeNativeMemory() }
                consumerBytes = mx.getThreadAllocatedBytes(id) - before
            }
        val producerId = Thread.currentThread().id
        val producerBefore = mx.getThreadAllocatedBytes(producerId)
        val start = System.nanoTime()
        consumer.start()
        repeat(ops) { queue.put(factory.allocate(SIZES[it % SIZES.size]).also { b -> b.writeByte(1) }) }
        consumer.join()
        val elapsed = System.nanoTime() - start
        val producerBytes = mx.getThreadAllocatedBytes(producerId) - producerBefore
        return elapsed.toDouble() / ops to (producerBytes + consumerBytes).toDouble() / ops
    }

    private companion object {
        val SIZES = intArrayOf(64, 512, 1350, 2048, 4096, 16_384, 65_536, 128, 1350, 8192)
    }
}
