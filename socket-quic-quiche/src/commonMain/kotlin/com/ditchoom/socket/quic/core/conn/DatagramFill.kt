package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes

/** Whether a slot was filled with a datagram, or its factory made memory without an address. */
internal enum class Filled { Yes, Unaddressable }

/**
 * The holder of this slot copies the [prefixLength] remaining bytes of [prefix] and then the [length] remaining bytes of
 * [payload] into it, stamped with [clock] when an [outgoing] age applies. New memory, when the slot's is missing or too
 * small, is made before the old is let go, so a factory that throws leaves the slot as it was.
 */
internal fun DatagramSlot.fill(
    factory: BufferFactory,
    clock: LoopClock,
    outgoing: AgeBound,
    prefix: ReadBuffer,
    prefixLength: Int,
    payload: ReadBuffer,
    length: Int,
): Filled {
    val whole = prefixLength + length
    val bytes =
        when (val memory = memoryFor(factory, whole)) {
            is SlotMemory.Made -> memory.bytes
            is SlotMemory.Unmade -> return Filled.Unaddressable
        }
    if (prefixLength > 0) copyInto(bytes, 0, prefix, prefixLength)
    if (length > 0) copyInto(bytes, prefixLength, payload, length)
    val queuedAt =
        when (outgoing) {
            is AgeBound.Unbounded -> 0L
            is AgeBound.Within -> clock.now().value
        }
    carries(whole, queuedAt)
    return Filled.Yes
}

private fun DatagramSlot.memoryFor(
    factory: BufferFactory,
    needed: Int,
): SlotMemory {
    val held = memory
    if (held is SlotMemory.Made && held.bytes.capacity >= needed) return held
    val buffer = factory.allocate(maxOf(needed, DATAGRAM_ROOM))
    return when (val made = NativeBytes.of(buffer)) {
        is Addressed.NotAddressable -> {
            buffer.freeNativeMemory()
            SlotMemory.Unmade
        }
        is Addressed.Native -> {
            val fresh = SlotMemory.Made(made.bytes)
            if (held is SlotMemory.Made) held.bytes.free()
            holds(fresh)
            fresh
        }
    }
}

/** Copies [length] remaining bytes of [from] to [to] at [offset]: by address, or through the buffer when it has none. */
private fun copyInto(
    to: NativeBytes,
    offset: Int,
    from: ReadBuffer,
    length: Int,
) = NativeBytes.addressing(
    from,
    { NativeBytes.copy(it + from.position(), to.address + offset, length) },
) {
    val start = from.position()
    to.owner.position(offset)
    to.owner.setLimit(offset + length)
    to.owner.write(from)
    from.position(start)
}
