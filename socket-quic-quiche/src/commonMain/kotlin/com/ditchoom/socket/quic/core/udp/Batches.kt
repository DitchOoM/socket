package com.ditchoom.socket.quic.core.udp

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.Pooled
import com.ditchoom.socket.quic.core.pool.Taken

internal class BatchLimits(
    val maxDatagrams: Int,
    val maxDatagramSize: Int,
)

internal sealed interface Room {
    object Open : Room

    object Full : Room
}

/**
 * The datagrams a pass has for the network, in order, in one contiguous buffer: slot `i` starts at
 * `i * maxDatagramSize`. Datagrams the network did not take stay for the next pass; a full batch stops the pass
 * from collecting more output, which is the back-pressure into quiche.
 */
internal class SendBatch(
    val storage: PlatformBuffer,
    /** The native address of [storage]'s first byte. */
    val address: Long,
    val limits: BatchLimits,
    /** Which thread-confined lane this batch is: 0 is the loop's, a caller's lane is its pool index plus one. */
    val lane: Int = 0,
) {
    private val lengths = IntArray(limits.maxDatagrams)
    private val targets = Array<SendTarget>(limits.maxDatagrams) { SendTarget.ConnectedPeer }
    private var committed = 0
    private var taken = 0

    val pending: Int get() = committed - taken

    fun room(): Room = if (committed == limits.maxDatagrams) Room.Full else Room.Open

    /** Where the next datagram is written, and how much it may hold. */
    fun nextOffset(): Int = committed * limits.maxDatagramSize

    fun commit(
        length: Int,
        target: SendTarget,
    ) {
        lengths[committed] = length
        targets[committed] = target
        committed++
    }

    /** The [n]th datagram not yet taken. */
    fun offsetOf(n: Int): Int = (taken + n) * limits.maxDatagramSize

    fun lengthOf(n: Int): Int = lengths[taken + n]

    fun targetOf(n: Int): SendTarget = targets[taken + n]

    /** The network took the first [count] pending datagrams. */
    fun took(count: Int) {
        taken += count
        if (taken == committed) {
            taken = 0
            committed = 0
        }
    }
}

internal class ReceiveBuffer(
    index: Int,
    val buffer: PlatformBuffer,
    /** The native address of [buffer]'s first byte. */
    val address: Long,
) : Pooled(index)

/** The address a datagram arrived on, when the socket reports it. */
internal sealed interface ArrivedOn {
    object Unreported : ArrivedOn

    class At(
        val local: PathKey,
    ) : ArrivedOn
}

/** Told each datagram of a [ReceiveBatch], in arrival order. [buffer] is valid only during the call. */
internal fun interface DatagramVisitor {
    fun datagram(
        buffer: ReceiveBuffer,
        length: Int,
        from: PathKey,
        to: ArrivedOn,
    )
}

/**
 * The datagrams of one receive, each in a buffer of [pool]. The pass visits them and they go back to the pool;
 * an empty pool means nothing more is received until buffers return.
 */
internal class ReceiveBatch(
    private val pool: FixedPool<ReceiveBuffer>,
    val limits: BatchLimits,
) {
    private val held = ArrayList<ReceiveBuffer>(limits.maxDatagrams)
    private val lengths = IntArray(limits.maxDatagrams)
    private val froms = ArrayList<PathKey>(limits.maxDatagrams)
    private val tos = ArrayList<ArrivedOn>(limits.maxDatagrams)

    val count: Int get() = held.size

    /** A buffer to receive the next datagram into, or [Taken.Empty] when the batch is full or the pool is empty. */
    fun take(): Taken = if (held.size == limits.maxDatagrams) Taken.Empty else pool.tryTake()

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

    /** Visits every datagram in arrival order, then gives every buffer back. */
    fun forEachDatagram(visit: DatagramVisitor) {
        try {
            for (i in held.indices) visit.datagram(held[i], lengths[i], froms[i], tos[i])
        } finally {
            for (i in held.indices) pool.give(held[i])
            held.clear()
            froms.clear()
            tos.clear()
        }
    }
}
