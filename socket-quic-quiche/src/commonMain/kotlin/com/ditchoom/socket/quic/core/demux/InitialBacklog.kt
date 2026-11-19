package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.NativeBytes

/** Whether the backlog took a datagram. */
internal enum class Held { Yes, Full }

/** What making a chunk of the backlog answered. */
internal sealed interface ChunkMade {
    /** At least [InitialBacklog.CHUNK_BYTES] of memory, for the backlog to keep. */
    class Made(
        val bytes: NativeBytes,
    ) : ChunkMade

    data object Refused : ChunkMade
}

/** [InitialBacklog.SLOTS_PER_CHUNK] slots in one block, filled and emptied in order. */
@LoopOnly
private class Chunk(
    bytes: NativeBytes,
) {
    private val slots = Array(InitialBacklog.SLOTS_PER_CHUNK) { bytes.window(it * InitialBacklog.SLOT_BYTES, InitialBacklog.SLOT_BYTES) }
    private val lengths = IntArray(InitialBacklog.SLOTS_PER_CHUNK)
    private val froms = Array(InitialBacklog.SLOTS_PER_CHUNK) { EMPTY }
    private val tos = Array<ArrivedOn>(InitialBacklog.SLOTS_PER_CHUNK) { ArrivedOn.Unreported }
    private var read = 0
    private var written = 0

    fun hasRoom(): Boolean = written < InitialBacklog.SLOTS_PER_CHUNK

    fun put(
        datagram: NativeBytes,
        length: Int,
        from: PathKey,
        to: ArrivedOn,
    ) {
        slots[written].copyFrom(datagram, 0, 0, length)
        lengths[written] = length
        froms[written] = from
        tos[written] = to
        written++
    }

    fun oldest(): NativeBytes = slots[read]

    fun oldestLength(): Int = lengths[read]

    fun oldestFrom(): PathKey = froms[read]

    fun oldestTo(): ArrivedOn = tos[read]

    /** Drops the oldest datagram; answers whether the chunk is empty, and then starts it over. */
    fun dropOldest(): Emptied {
        froms[read] = EMPTY
        tos[read] = ArrivedOn.Unreported
        read++
        if (read < written) return Emptied.No
        read = 0
        written = 0
        return Emptied.Yes
    }
}

private enum class Emptied { Yes, No }

/** Whether the backlog has a slot for one more Initial. */
private enum class Tail { Room, Full }

/**
 * The Initials waiting for an admission chunk, oldest first, in chunks of [SLOTS_PER_CHUNK] slots: holding one copies it
 * into the next free slot. The backlog starts with one chunk and takes another from [chunks] only while Initials wait
 * past the ones it has, up to [capacity] slots; a chunk emptied is kept for the next wave, so the backlog holds the
 * chunks of its largest wave and a wave no larger allocates nothing. A sub-part of [Admission]; loop thread only.
 */
@LoopOnly
internal class InitialBacklog(
    private val capacity: Int,
    private val chunks: () -> ChunkMade,
) {
    private val filling = ArrayList<Chunk>()
    private val spare = ArrayList<Chunk>()
    private val maxChunks = (capacity + SLOTS_PER_CHUNK - 1) / SLOTS_PER_CHUNK
    private var made = 0

    init {
        when (val first = chunks()) {
            is ChunkMade.Made -> spare.add(chunkOf(first.bytes))
            is ChunkMade.Refused -> Unit
        }
    }

    /** Datagrams held. */
    var size = 0
        private set

    /** Copies the first [length] bytes of [datagram], which came [from] and arrived [to], to the back of the backlog. */
    fun hold(
        datagram: NativeBytes,
        length: Int,
        from: PathKey,
        to: ArrivedOn,
    ): Held {
        if (size == capacity || length > SLOT_BYTES || tail() == Tail.Full) return Held.Full
        filling[filling.size - 1].put(datagram, length, from, to)
        size++
        return Held.Yes
    }

    /** The oldest datagram held, valid until [dropOldest]. */
    fun oldest(): NativeBytes = filling[0].oldest()

    fun oldestLength(): Int = filling[0].oldestLength()

    fun oldestFrom(): PathKey = filling[0].oldestFrom()

    fun oldestTo(): ArrivedOn = filling[0].oldestTo()

    fun dropOldest() {
        val head = filling[0]
        size--
        when (head.dropOldest()) {
            Emptied.No -> Unit
            Emptied.Yes -> spare.add(filling.removeAt(0))
        }
    }

    /** Chunks made so far: diagnostics and tests. */
    fun chunksMade(): Int = made

    /** Makes room at the back: the newest chunk's next slot, else a spare chunk or a new one. */
    private fun tail(): Tail {
        if (filling.isNotEmpty() && filling[filling.size - 1].hasRoom()) return Tail.Room
        if (spare.isNotEmpty()) {
            filling.add(spare.removeAt(spare.size - 1))
            return Tail.Room
        }
        if (made == maxChunks) return Tail.Full
        return when (val grown = chunks()) {
            is ChunkMade.Made -> {
                filling.add(chunkOf(grown.bytes))
                Tail.Room
            }
            is ChunkMade.Refused -> Tail.Full
        }
    }

    private fun chunkOf(bytes: NativeBytes): Chunk {
        made++
        return Chunk(bytes)
    }

    companion object {
        /** A slot holds the largest datagram the server receives. */
        const val SLOT_BYTES = 1500

        /** A chunk is one of the buffer pool's size classes, packed with slots. */
        const val CHUNK_BYTES = 128 * 1024
        const val SLOTS_PER_CHUNK = CHUNK_BYTES / SLOT_BYTES
    }
}

/** What an empty slot holds instead of a sender; never read. */
private val EMPTY = PathKey(family = 0, port = 0, hi = 0L, lo = 0L)
