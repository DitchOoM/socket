package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.edge.addressOf
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import java.nio.Buffer
import java.nio.ByteBuffer

/** Whether the backlog took a datagram. */
internal sealed interface Held {
    object Yes : Held

    object Full : Held
}

/**
 * The Initials a pass had no room to admit, oldest first, in slots allocated once: holding one copies it into the
 * next free slot and allocates nothing. Loop thread only.
 */
internal class InitialBacklog(
    private val capacity: Int,
    datagramSize: Int,
    factory: BufferFactory,
) {
    private val slots =
        Array(capacity) {
            val buffer = factory.allocate(datagramSize)
            ReceiveBuffer(it, buffer, addressOf(buffer))
        }
    private val views = Array<ByteBuffer>(capacity) { viewOf(slots[it]) }
    private val lengths = IntArray(capacity)
    private val froms = Array(capacity) { NO_PEER }
    private var first = 0

    /** Datagrams held. */
    var size = 0
        private set

    val isEmpty: Boolean get() = size == 0

    /** Copies the first [length] bytes of [datagram] to the back of the backlog. */
    fun hold(
        datagram: ReceiveBuffer,
        length: Int,
        from: PathKey,
    ): Held {
        if (size == capacity) return Held.Full
        val at = (first + size) % capacity
        val source = viewOf(datagram)
        (source as Buffer).limit(length).position(0)
        val target = views[at]
        (target as Buffer).clear()
        target.put(source)
        lengths[at] = length
        froms[at] = from
        size++
        return Held.Yes
    }

    /** The oldest datagram held, valid until [dropOldest]. */
    fun oldest(): ReceiveBuffer = slots[first]

    fun oldestLength(): Int = lengths[first]

    fun oldestFrom(): PathKey = froms[first]

    fun dropOldest() {
        froms[first] = NO_PEER
        first = (first + 1) % capacity
        size--
    }

    fun free() {
        for (slot in slots) slot.buffer.freeNativeMemory()
    }

    private fun viewOf(buffer: ReceiveBuffer): ByteBuffer = (buffer.buffer.unwrapFully() as BaseJvmBuffer).byteBuffer

    private companion object {
        val NO_PEER = PathKey(family = 0, port = 0, hi = 0L, lo = 0L)
    }
}
