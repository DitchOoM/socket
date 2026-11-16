package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.core.observe.CallerSide
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress

/** How the peer's side of a typed stream stands. */
internal sealed interface Ending {
    data object Open : Ending

    /** The peer's FIN arrived. */
    data object Finished : Ending

    /** The peer reset the stream. */
    data object Reset : Ending

    /** A frame was refused; the stream's reads fail with [failure] from now on. */
    class Broken(
        val failure: TypedFailure,
    ) : Ending
}

/**
 * The bytes a typed stream has received and not yet decoded, in one native buffer: the connection receives into its free
 * tail, the caller peeks and decodes from its head, and one never touches it while the other may (see [StreamTransfer]).
 * It is a [StreamProcessor] so the codec's own frame detection reads it in place; it holds one buffer, so a frame is
 * always contiguous and decodes without a copy.
 */
@CallerSide
internal class FrameAccumulator(
    private val factory: BufferFactory,
    private val bound: Int,
) : StreamProcessor {
    private var storage: PlatformBuffer = allocate(minOf(INITIAL_BYTES, bound))
    private var address: Long = addressOf(storage)
    private var readAt = 0
    private var writeAt = 0

    private var ending: Ending = Ending.Open

    /** How the peer's side stands. */
    fun ending(): Ending = ending

    /** The peer's FIN arrived. */
    fun peerFinished() {
        ending = Ending.Finished
    }

    /** The peer reset the stream. */
    fun peerReset() {
        ending = Ending.Reset
    }

    /** A frame was refused: reads fail with [failure] from now on. */
    fun broken(failure: TypedFailure) {
        ending = Ending.Broken(failure)
    }

    /** How many bytes can be received into the tail without moving anything. */
    fun free(): Int = storage.capacity - writeAt

    /** Where the next received byte goes. */
    fun tailAddress(): Long = address + writeAt

    /** The connection received [count] bytes into the tail. */
    fun filled(count: Int) {
        writeAt += count
    }

    /**
     * Makes the tail able to take at least one more byte, and at least enough for a frame of [frame] bytes in all: the
     * bytes already held move to the front, or the storage grows, never past the bound.
     */
    fun makeRoom(frame: Int) {
        if (readAt == writeAt) {
            readAt = 0
            writeAt = 0
        }
        val wanted = minOf(maxOf(frame, held() + MIN_TAIL_BYTES), bound)
        if (storage.capacity < wanted) {
            grow(maxOf(wanted, minOf(storage.capacity * 2, bound)))
        } else if (storage.capacity - readAt < wanted || free() == 0) {
            compact()
        }
    }

    private fun compact() {
        val held = writeAt - readAt
        var i = 0
        while (i + Long.SIZE_BYTES <= held) {
            storage[i] = storage.getLong(readAt + i)
            i += Long.SIZE_BYTES
        }
        while (i < held) {
            storage[i] = storage[readAt + i]
            i++
        }
        readAt = 0
        writeAt = held
    }

    private fun grow(capacity: Int) {
        val bigger = allocate(capacity)
        val held = writeAt - readAt
        var i = 0
        while (i + Long.SIZE_BYTES <= held) {
            bigger[i] = storage.getLong(readAt + i)
            i += Long.SIZE_BYTES
        }
        while (i < held) {
            bigger[i] = storage[readAt + i]
            i++
        }
        storage.freeNativeMemory()
        storage = bigger
        address = addressOf(bigger)
        readAt = 0
        writeAt = held
    }

    /** Decodes the [size] bytes at the head with [decoder] and drops them, whatever [decoder] does. */
    fun <T> decode(
        decoder: Decoder<T>,
        context: DecodeContext,
        size: Int,
    ): T {
        val frame = readAt
        storage.position(frame)
        storage.setLimit(frame + size)
        try {
            return decoder.decode(storage, context)
        } finally {
            storage.setLimit(storage.capacity)
            readAt = frame + size
        }
    }

    /** Frees the native memory. Nothing receives into or decodes from it afterwards. */
    fun freeStorage() = storage.freeNativeMemory()

    private fun allocate(size: Int): PlatformBuffer = factory.allocate(size).also { it.setLimit(it.capacity) }

    private fun addressOf(buffer: PlatformBuffer): Long =
        when (val at = NativeBytes.addressOf(buffer)) {
            is ReadAddress.At -> at.address
            is ReadAddress.NotAddressable -> raise(Raised.FactoryNotNative(buffer), QuicCloseCircumstance.Ended)
        }

    override fun append(chunk: ReadBuffer) {
        val size = chunk.remaining()
        makeRoom(held() + size)
        val base = chunk.position()
        for (i in 0 until size) storage[writeAt + i] = chunk[base + i]
        writeAt += size
        chunk.position(base + size)
    }

    private fun held(): Int = writeAt - readAt

    override fun available(): Int = held()

    override fun peekByte(offset: Int): Byte = storage[readAt + offset]

    override fun peekShort(offset: Int): Short = storage.getShort(readAt + offset)

    override fun peekInt(offset: Int): Int = storage.getInt(readAt + offset)

    override fun peekLong(offset: Int): Long = storage.getLong(readAt + offset)

    override fun peekBuffer(
        offset: Int,
        maxBytes: Int,
    ): ReadBuffer {
        val start = readAt + offset
        val size = minOf(maxBytes, writeAt - start)
        storage.position(start)
        storage.setLimit(start + size)
        val view = storage.slice()
        storage.setLimit(storage.capacity)
        return view
    }

    override fun peekMismatch(pattern: ReadBuffer): Int {
        val size = pattern.remaining()
        val base = pattern.position()
        val common = minOf(size, held())
        for (i in 0 until common) if (storage[readAt + i] != pattern[base + i]) return i
        return if (held() < size) held() else MATCHED
    }

    override fun peekMatches(pattern: ReadBuffer): Boolean = peekMismatch(pattern) == MATCHED

    override fun readByte(): Byte = storage[readAt].also { readAt += Byte.SIZE_BYTES }

    override fun readUnsignedByte(): Int = readByte().toInt() and BYTE_MASK

    override fun readShort(): Short = peekShort(0).also { readAt += Short.SIZE_BYTES }

    override fun readInt(): Int = peekInt(0).also { readAt += Int.SIZE_BYTES }

    override fun readLong(): Long = peekLong(0).also { readAt += Long.SIZE_BYTES }

    override fun readBuffer(size: Int): ReadBuffer = peekBuffer(0, size).also { readAt += size }

    override fun <T> readBufferScoped(
        size: Int,
        block: ReadBuffer.() -> T,
    ): T {
        val frame = readAt
        storage.position(frame)
        storage.setLimit(frame + size)
        try {
            return storage.block()
        } finally {
            storage.setLimit(storage.capacity)
            readAt = frame + size
        }
    }

    override fun skip(count: Int) {
        readAt += count
    }

    /** Forgets every byte held; the storage stays. */
    override fun release() {
        readAt = 0
        writeAt = 0
    }

    private companion object {
        const val INITIAL_BYTES = 4_096
        const val MIN_TAIL_BYTES = 512
        const val BYTE_MASK = 0xFF

        /** What the framework's [StreamProcessor.peekMismatch] answers when the pattern matches: no index. */
        const val MATCHED = -(1)
    }
}
