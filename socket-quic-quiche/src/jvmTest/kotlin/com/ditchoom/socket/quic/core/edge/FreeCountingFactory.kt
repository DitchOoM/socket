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
 * edge's memory to being freed exactly once, by its one owner. Only buffers allocated from one of [sites] are kept.
 */
internal class FreeCountingFactory(
    private val inner: BufferFactory,
    private val sites: Set<Site> = setOf(Site.EdgeMemory),
    private val refusal: Refusal = Refusal.None,
) : BufferFactory {
    private val edge = ConcurrentLinkedQueue<CountedBuffer>()
    private val refusalSeen = AtomicInteger()

    /** Which allocation, if any, the factory refuses. */
    sealed interface Refusal {
        data object None : Refusal

        /** The [nth] allocation (from 1) from [site] throws [Refused]. */
        class At(
            val site: Site,
            val nth: Int,
        ) : Refusal
    }

    class Refused : RuntimeException("allocation refused")

    /** The buffers allocated from one of [sites], so far. */
    fun edgeBuffers(): List<CountedBuffer> = edge.toList()

    /** Where a kept buffer was allocated: a frame of its allocation's stack. */
    enum class Site(
        val matches: (StackTraceElement) -> Boolean,
    ) {
        EdgeMemory({ it.className == "com.ditchoom.socket.quic.core.edge.EdgeMemory" }),
        SockAddr({ it.methodName == "encodeToNative" }),
        AddressTokens({ it.className == "com.ditchoom.socket.quic.core.demux.AddressTokens" }),
        ConnectArgs({ it.methodName == "nullTerminated" || it.methodName == "generateScid" }),

        /** Any allocation the core makes. */
        Core({ it.className.startsWith(CORE) && !it.className.startsWith(TEST_FACTORY) }),
    }

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        val stack = Throwable().stackTrace
        when (refusal) {
            is Refusal.None -> Unit
            is Refusal.At ->
                if (stack.any(refusal.site.matches) && refusalSeen.incrementAndGet() == refusal.nth) throw Refused()
        }
        val made = inner.allocate(size, byteOrder)
        val site = sites.firstOrNull { stack.any(it.matches) } ?: return made
        return CountedBuffer(made, site, placeOf(stack)).also { edge.add(it) }
    }

    /** The bytes of kept buffers not yet freed, as the inner factory sized them. */
    fun heldBytes(): Long = edge.filter { it.frees.get() == 0 }.sumOf { it.capacity.toLong() }

    /** [heldBytes] by the core frame that allocated each buffer, largest first. */
    fun heldByPlace(): Map<String, Long> =
        edge
            .filter { it.frees.get() == 0 }
            .groupBy { it.place }
            .mapValues { (_, buffers) -> buffers.sumOf { it.capacity.toLong() } }

    private fun placeOf(stack: Array<StackTraceElement>): String =
        stack
            .firstOrNull {
                it.className.startsWith(
                    CORE,
                ) &&
                    !it.className.startsWith(TEST_FACTORY) &&
                    !it.className.endsWith(".EdgeMemory")
            }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            ?: "elsewhere"

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
        val site: Site,
        /** The core frame that allocated it. */
        val place: String,
    ) : PlatformBuffer by inner {
        val frees = AtomicInteger()
        val firstFreedOn = AtomicReference<Thread>()

        override fun freeNativeMemory() {
            firstFreedOn.compareAndSet(null, Thread.currentThread())
            frees.incrementAndGet()
            inner.freeNativeMemory()
        }

        override fun toString(): String = "$site(${inner.capacity} B, freed ${frees.get()}x)"
    }
}

private const val CORE = "com.ditchoom.socket.quic.core."
private const val TEST_FACTORY = "com.ditchoom.socket.quic.core.edge.FreeCountingFactory"
