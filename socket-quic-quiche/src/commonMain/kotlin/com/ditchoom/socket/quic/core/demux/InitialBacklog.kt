package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.udp.NativeBytes

/** Whether the backlog took a datagram. */
internal enum class Held { Yes, Full }

/**
 * The Initials waiting for an admission chunk, oldest first, in slots allocated once: holding one copies it into the
 * next free slot and allocates nothing. A sub-part of [Admission]; loop thread only.
 */
@LoopOnly
internal class InitialBacklog(
    private val slots: List<NativeBytes>,
) {
    private val capacity = slots.size
    private val lengths = IntArray(capacity)
    private val froms = Array(capacity) { EMPTY }
    private var first = 0

    /** Datagrams held. */
    var size = 0
        private set

    /** Copies the first [length] bytes of [datagram] to the back of the backlog. */
    fun hold(
        datagram: NativeBytes,
        length: Int,
        from: PathKey,
    ): Held {
        if (size == capacity || length > slots[0].capacity) return Held.Full
        val at = (first + size) % capacity
        slots[at].copyFrom(datagram, 0, 0, length)
        lengths[at] = length
        froms[at] = from
        size++
        return Held.Yes
    }

    /** The oldest datagram held, valid until [dropOldest]. */
    fun oldest(): NativeBytes = slots[first]

    fun oldestLength(): Int = lengths[first]

    fun oldestFrom(): PathKey = froms[first]

    fun dropOldest() {
        froms[first] = EMPTY
        first = (first + 1) % capacity
        size--
    }

    fun free() {
        for (slot in slots) slot.free()
    }

    private companion object {
        /** What an empty slot holds instead of a sender; never read. */
        val EMPTY = PathKey(family = 0, port = 0, hi = 0L, lo = 0L)
    }
}
