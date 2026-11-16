package com.ditchoom.socket.quic.core.udp

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
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

    /** Writes [value] at [offset] in native order: a native `size_t` or a tag, as [longAt] reads it back. */
    fun putLong(
        offset: Int,
        value: Long,
    ) = UnsafeMemory.putLong(address + offset, value)

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

        /** Answers [at] with [buffer]'s address, or [notAddressable]; allocates nothing, for a buffer used once. */
        inline fun <R> addressing(
            buffer: ReadBuffer,
            at: (address: Long) -> R,
            notAddressable: () -> R,
        ): R {
            val access = buffer.nativeMemoryAccess
            return if (access == null) notAddressable() else at(access.nativeAddress.toLong())
        }

        /** The address of the first byte of a caller's [buffer], read here and nowhere else. */
        fun addressOf(buffer: ReadBuffer): ReadAddress {
            val access = buffer.nativeMemoryAccess
            return if (access == null) ReadAddress.NotAddressable else ReadAddress.At(access.nativeAddress.toLong())
        }
    }
}

/** Where a caller's buffer lives, when quiche can read it in place. */
internal sealed interface ReadAddress {
    class At(
        val address: Long,
    ) : ReadAddress

    data object NotAddressable : ReadAddress
}

/** Whether a buffer can be used as wire memory. */
internal sealed interface Addressed {
    class Native(
        val bytes: NativeBytes,
    ) : Addressed

    /** Heap or otherwise unaddressable memory: a factory that makes it cannot carry a connection. */
    data object NotAddressable : Addressed
}
