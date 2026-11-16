package com.ditchoom.socket.quic.core.udp

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.UnsafeMemory
import com.ditchoom.buffer.nativeMemoryAccess

private const val BYTE_MASK = 0xFF

/**
 * Memory that has an address: the only form in which anything below the edge holds wire memory. A batch, a pool
 * slot or the stream layer takes a [NativeBytes], so no I/O implementation can be handed a buffer it would have to
 * cast.
 */
internal class NativeBytes private constructor(
    val address: Long,
    val capacity: Int,
    internal val owner: PlatformBuffer,
) {
    /** Releases the native memory. The bytes are not used afterwards. */
    fun free() = owner.freeNativeMemory()

    /** Copies [length] bytes from [from] at [fromOffset] to this memory at [toOffset]. Allocates nothing. */
    fun copyFrom(
        from: NativeBytes,
        fromOffset: Int,
        toOffset: Int,
        length: Int,
    ) = UnsafeMemory.copyMemory(from.address + fromOffset, address + toOffset, length.toLong())

    /** The byte at [offset], as an unsigned value. */
    fun byteAt(offset: Int): Int = UnsafeMemory.getByte(address + offset).toInt() and BYTE_MASK

    /** The eight bytes at [offset], in native order: a tag, never a number to compute with. */
    fun longAt(offset: Int): Long = UnsafeMemory.getLong(address + offset)

    companion object {
        /** Reads [buffer]'s address once, here; a buffer without one is [Addressed.NotAddressable]. */
        fun of(buffer: PlatformBuffer): Addressed {
            val access = buffer.nativeMemoryAccess
            return if (access == null) {
                Addressed.NotAddressable
            } else {
                Addressed.Native(NativeBytes(access.nativeAddress.toLong(), buffer.capacity, buffer))
            }
        }
    }
}

/** Whether a buffer can be used as wire memory. */
internal sealed interface Addressed {
    class Native(
        val bytes: NativeBytes,
    ) : Addressed

    /** Heap or otherwise unaddressable memory: a factory that makes it cannot carry a connection. */
    data object NotAddressable : Addressed
}
