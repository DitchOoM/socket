package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.Taken

/**
 * The datagrams of one receive, each in a buffer of [pool]. The loop reads them by position and then calls
 * [release], which gives every buffer back; an empty pool means nothing more is received until buffers return.
 */
@LoopOnly
internal class ReceiveBatch(
    private val pool: FixedPool<ReceiveBuffer>,
    val limits: BatchLimits,
) {
    private val held = ArrayList<ReceiveBuffer>(limits.maxDatagrams)
    private val lengths = IntArray(limits.maxDatagrams)
    private val froms = ArrayList<PathKey>(limits.maxDatagrams)
    private val tos = ArrayList<ArrivedOn>(limits.maxDatagrams)

    /** The slots a native socket receives into; they stay armed between receives. */
    val slots = ReceiveSlots(pool, minOf(NativeIo.MAX_RECEIVE, pool.size, limits.maxDatagrams))

    val count: Int get() = held.size

    /** A buffer to receive the next datagram into, or [Taken.Empty] when the batch is full or the pool is empty. */
    fun take(): Taken = if (held.size == limits.maxDatagrams) Taken.Empty else pool.tryTake()

    /**
     * A buffer to copy the next datagram into, for an io with no receive records of its own: from the pool, else from a
     * slot a native socket armed, which its next arm refills. [Taken.Empty] only when the batch is full.
     */
    fun takeToCopyInto(): Taken {
        val taken = take()
        return if (taken is ReceiveBuffer || held.size == limits.maxDatagrams) taken else slots.takeArmed()
    }

    /** A buffer from [take] that received nothing. */
    fun giveBack(buffer: ReceiveBuffer) = pool.give(buffer)

    fun received(
        buffer: ReceiveBuffer,
        length: Int,
        from: PathKey,
        to: ArrivedOn,
    ) {
        lengths[held.size] = length
        froms.add(from)
        tos.add(to)
        held.add(buffer)
    }

    fun bufferAt(i: Int): ReceiveBuffer = held[i]

    fun lengthAt(i: Int): Int = lengths[i]

    fun fromAt(i: Int): PathKey = froms[i]

    fun toAt(i: Int): ArrivedOn = tos[i]

    /** Gives the armed slots' buffers back to the pool: the loop's last step before its memory is freed. */
    fun disarm() = slots.disarm()

    /** Gives every buffer back; the datagrams are not read afterwards. */
    fun release() {
        for (i in held.indices) pool.give(held[i])
        held.clear()
        froms.clear()
        tos.clear()
    }
}
