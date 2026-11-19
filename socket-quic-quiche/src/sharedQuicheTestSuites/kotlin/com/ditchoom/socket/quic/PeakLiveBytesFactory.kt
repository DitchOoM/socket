package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.CloseableBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic

/**
 * A [BufferFactory] over [BufferFactory.deterministic] that knows the most bytes its buffers held at once.
 *
 * Measured at every allocation: the buffers not yet freed, by capacity. Not a pool and not known to be one,
 * so a driver given it allocates every buffer here. Confined to one thread, as the sim under `runTest` is.
 */
internal class PeakLiveBytesFactory private constructor() : BufferFactory {
    private val leaf = BufferFactory.deterministic()
    private val live = ArrayList<CloseableBuffer>()
    private val capacities = ArrayList<Int>()

    /** The most bytes held by buffers from this factory at any one allocation. */
    var peakBytes: Long = 0L
        private set

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        val buffer = leaf.allocate(size, byteOrder)
        var held = 0L
        var kept = 0
        for (i in live.indices) {
            if (live[i].isFreed) continue
            live[kept] = live[i]
            capacities[kept] = capacities[i]
            held += capacities[i]
            kept++
        }
        while (live.size > kept) {
            live.removeAt(live.lastIndex)
            capacities.removeAt(capacities.lastIndex)
        }
        live += buffer as CloseableBuffer
        capacities += buffer.capacity
        held += buffer.capacity
        if (held > peakBytes) peakBytes = held
        return buffer
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer = leaf.wrap(array, byteOrder)

    /** Whether this platform's deterministic buffers say when they are freed, which the measure needs. */
    sealed interface OnThisPlatform {
        class Measurable(
            val factory: PeakLiveBytesFactory,
        ) : OnThisPlatform

        /** [bufferType] is released by its runtime, not by `freeNativeMemory()`, so its release cannot be seen. */
        class Unobservable(
            val bufferType: String,
        ) : OnThisPlatform
    }

    companion object {
        fun onThisPlatform(): OnThisPlatform {
            val probe = BufferFactory.deterministic().allocate(1)
            val type = probe::class.simpleName ?: "<anonymous>"
            val measurable = probe is CloseableBuffer
            probe.freeNativeMemory()
            return if (measurable) OnThisPlatform.Measurable(PeakLiveBytesFactory()) else OnThisPlatform.Unobservable(type)
        }
    }
}
