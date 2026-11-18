package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * A factory over [inner] whose buffers count their frees and keep the thread of the first, so a test can hold an
 * edge's memory to being freed exactly once, by its one owner. Only buffers an [EdgeMemory] allocated are kept.
 */
internal class FreeCountingFactory(
    private val inner: BufferFactory,
) : BufferFactory {
    private val edge = ConcurrentLinkedQueue<CountedBuffer>()

    /** The buffers an [EdgeMemory] took from this factory, so far. */
    fun edgeBuffers(): List<CountedBuffer> = edge.toList()

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        val made = inner.allocate(size, byteOrder)
        if (Throwable().stackTrace.none { it.className == EdgeMemory::class.java.name }) return made
        return CountedBuffer(made).also { edge.add(it) }
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer = inner.wrap(array, byteOrder)

    /** Waits up to [bound] for every edge buffer to have been freed; returns those still not freed. */
    suspend fun unfreedAfter(bound: Duration): List<CountedBuffer> {
        val mark = TimeSource.Monotonic.markNow()
        while (mark.elapsedNow() < bound && edge.any { it.frees.get() == 0 }) delay(10.milliseconds)
        return edge.filter { it.frees.get() == 0 }
    }

    class CountedBuffer(
        private val inner: PlatformBuffer,
    ) : PlatformBuffer by inner {
        val frees = AtomicInteger()
        val firstFreedOn = AtomicReference<Thread>()

        override fun freeNativeMemory() {
            firstFreedOn.compareAndSet(null, Thread.currentThread())
            frees.incrementAndGet()
            inner.freeNativeMemory()
        }
    }
}
