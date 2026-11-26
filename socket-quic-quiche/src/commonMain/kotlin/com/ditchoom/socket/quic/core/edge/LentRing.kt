@file:OptIn(ExperimentalAtomicApi::class, ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.quic.core.observe.Queue
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * A bounded single-producer, single-consumer ring of datagrams between a lent channel's coroutine and its loop, each in
 * a slot of memory of its own: the producer writes the slot [next] names and [publish]es it, the consumer reads the
 * oldest ([head]) and [pop]s it. [slots] is a power of two in size, so a position that wraps past the largest Int still
 * names its slot. Nothing is allocated per datagram; the slots are freed once, by the last of its two users out.
 */
@Queue
internal class LentRing(
    private val slots: List<NativeBytes>,
    firstAddress: SocketAddress,
) {
    private val capacity = slots.size
    private val lengths = IntArray(capacity)
    private val addresses = Array(capacity) { firstAddress }
    private val added = AtomicInt(0)
    private val taken = AtomicInt(0)
    private val usersOut = AtomicInt(0)

    /** Producer: whether one more fits. */
    fun hasRoom(): Boolean = added.load() - taken.load() < capacity

    /** Producer, after [hasRoom]: the memory the next datagram is written into. */
    fun next(): NativeBytes = slots[added.load() and (capacity - 1)]

    /** Producer: the datagram written into [next] is [length] bytes, from or to [address]. */
    fun publish(
        length: Int,
        address: SocketAddress,
    ) {
        val at = added.load()
        lengths[at and (capacity - 1)] = length
        addresses[at and (capacity - 1)] = address
        added.store(at + 1)
    }

    /** Consumer: whether nothing waits. */
    fun isEmpty(): Boolean = taken.load() == added.load()

    /** Consumer, unless [isEmpty]: the oldest datagram's memory. */
    fun head(): NativeBytes = slots[taken.load() and (capacity - 1)]

    fun headLength(): Int = lengths[taken.load() and (capacity - 1)]

    fun headAddress(): SocketAddress = addresses[taken.load() and (capacity - 1)]

    /** Consumer: the oldest datagram was taken, and its slot is free. */
    fun pop() = taken.store(taken.load() + 1)

    /** Each of the ring's two users calls this once it is done with it: the second frees the slots. */
    fun leave() {
        if (usersOut.incrementAndFetch() == 2) for (slot in slots) slot.free()
    }
}
