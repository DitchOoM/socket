package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Encoder
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.core.observe.CallerSide
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress

/** What [FrameScratch.encode] did with a value. */
internal enum class Encoded {
    /** The value's bytes are in the scratch. */
    Done,

    /** The value needs more bytes than the stream's bound. */
    TooLarge,
}

/**
 * The one native buffer a typed stream encodes each outgoing frame into, on the caller's thread, and that quiche then
 * reads from in place: a send takes the frame's bytes from here and nothing copies them first. It grows, never past the
 * bound, when a value does not fit; a value that fits leaves it as it was.
 */
@CallerSide
internal class FrameScratch(
    private val factory: BufferFactory,
    private val bound: Int,
) {
    private var storage: PlatformBuffer = allocate(minOf(INITIAL_BYTES, bound))
    private var address: Long = addressOf(storage)

    /** How many bytes of [storage] the last encoded frame took. */
    var length = 0
        private set

    /** Where the frame's first byte is. */
    fun address(): Long = address

    /** Encodes [value] with [encoder] into the scratch from its start, growing it while the value does not fit. */
    fun <T> encode(
        encoder: Encoder<T>,
        value: T,
        context: EncodeContext,
    ): Encoded {
        while (true) {
            storage.resetForWrite()
            val fitted =
                overflowing(overflow = { false }) {
                    encoder.encode(storage, value, context)
                    length = storage.position()
                    true
                }
            if (fitted) return Encoded.Done
            if (!grow(encoder.sizeHint(value, context))) return Encoded.TooLarge
        }
    }

    /** A frame that carries no bytes: a stream's FIN alone. */
    fun empty() {
        length = 0
    }

    private fun grow(hint: Int): Boolean {
        val current = storage.capacity
        if (current >= bound) return false
        val bigger = allocate(minOf(maxOf(current * 2, hint), bound))
        storage.freeNativeMemory()
        storage = bigger
        address = addressOf(bigger)
        return true
    }

    /** Frees the native memory. Nothing sends from it afterwards. */
    fun freeStorage() = storage.freeNativeMemory()

    private fun allocate(size: Int): PlatformBuffer = factory.allocate(size).also { it.setLimit(it.capacity) }

    private fun addressOf(buffer: PlatformBuffer): Long =
        when (val at = NativeBytes.addressOf(buffer)) {
            is ReadAddress.At -> at.address
            is ReadAddress.NotAddressable -> raise(Raised.FactoryNotNative(buffer), QuicCloseCircumstance.Ended)
        }

    private companion object {
        const val INITIAL_BYTES = 1_024
    }
}
