package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.udp.MadeMemory
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.nativeBytes

/** The memory a slot holds for a datagram, which is its own answer, or how the connection's factory failed to make it. */
internal sealed interface Readied {
    /** The factory threw, or made memory without an address; the slot is as it was, and nothing is leaked. */
    class Failed(
        val failure: Throwable,
    ) : Readied
}

/**
 * The holder of this slot makes sure it has memory for [needed] bytes. New memory, when the slot's is missing or too
 * small, is made before the old is let go, so a factory that fails leaves the slot as it was.
 */
internal fun DatagramSlot.ready(
    factory: BufferFactory,
    needed: Int,
): Readied {
    val held = memory
    if (held is SlotMemory.Made && held.bytes.capacity >= needed) return held
    return when (val made = factory.nativeBytes(maxOf(needed, DATAGRAM_ROOM))) {
        is MadeMemory.Failed -> Readied.Failed(made.failure)
        is MadeMemory.Made -> {
            if (held is SlotMemory.Made) held.bytes.free()
            SlotMemory.Made(made.bytes).also { holds(it) }
        }
    }
}

/**
 * The holder of this slot copies the [prefixLength] remaining bytes of [prefix] and then the [length] remaining bytes of
 * [payload] into its [memory], which [ready] made, stamped with [clock]'s time.
 */
internal fun DatagramSlot.fill(
    memory: SlotMemory.Made,
    clock: LoopClock,
    prefix: ReadBuffer,
    prefixLength: Int,
    payload: ReadBuffer,
    length: Int,
) {
    val bytes = memory.bytes
    if (prefixLength > 0) copyInto(bytes, 0, prefix, prefixLength)
    if (length > 0) copyInto(bytes, prefixLength, payload, length)
    carries(prefixLength + length, clock.now())
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
