package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.NativeBytes

/** Whether the backlog took a datagram. */
internal enum class Held { Yes, Full }

/** What a [BacklogChunks] answers. */
internal sealed interface ChunkMade {
    /** At least [InitialBacklog.CHUNK_BYTES] of memory, for the backlog to keep. */
    class Made(
        val bytes: NativeBytes,
    ) : ChunkMade

    data object Refused : ChunkMade
}

/** Makes the backlog's chunks: the first where the backlog is built, the rest on the loop thread. */
internal fun interface BacklogChunks {
    fun make(): ChunkMade
}

/** [InitialBacklog.SLOTS_PER_CHUNK] slots in one block, filled and emptied in order. */
private class Chunk(
    bytes: NativeBytes,
) {
    val slots = Array(InitialBacklog.SLOTS_PER_CHUNK) { bytes.window(it * InitialBacklog.SLOT_BYTES, InitialBacklog.SLOT_BYTES) }
    val lengths = IntArray(InitialBacklog.SLOTS_PER_CHUNK)
    val froms = Array(InitialBacklog.SLOTS_PER_CHUNK) { EMPTY }
    val tos = Array<ArrivedOn>(InitialBacklog.SLOTS_PER_CHUNK) { ArrivedOn.Unreported }
    var read = 0
    var written = 0
}

/** Whether the newest chunk has a free slot. */
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
    private val chunks: BacklogChunks,
) {
    private val filling = ArrayList<Chunk>()
    private val spare = ArrayList<Chunk>()
    private val maxChunks = (capacity + SLOTS_PER_CHUNK - 1) / SLOTS_PER_CHUNK
    private var made = 0

    init {
        when (val first = chunks.make()) {
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
        val chunk = filling[filling.size - 1]
        val at = chunk.written
        chunk.slots[at].copyFrom(datagram, 0, 0, length)
        chunk.lengths[at] = length
        chunk.froms[at] = from
        chunk.tos[at] = to
        chunk.written = at + 1
        size++
        return Held.Yes
    }

    /** The oldest datagram held, valid until [dropOldest]. */
    fun oldest(): NativeBytes = filling[0].slots[filling[0].read]

    fun oldestLength(): Int = filling[0].lengths[filling[0].read]

    fun oldestFrom(): PathKey = filling[0].froms[filling[0].read]

    fun oldestTo(): ArrivedOn = filling[0].tos[filling[0].read]

    fun dropOldest() {
        val head = filling[0]
        head.froms[head.read] = EMPTY
        head.tos[head.read] = ArrivedOn.Unreported
        head.read++
        size--
        if (head.read == head.written) {
            filling.removeAt(0)
            head.read = 0
            head.written = 0
            spare.add(head)
        }
    }

    /** Chunks made so far: diagnostics and tests. */
    fun chunksMade(): Int = made

    /** Makes room at the back: the newest chunk's next slot, else a spare chunk or a new one. */
    private fun tail(): Tail {
        if (filling.isNotEmpty() && filling[filling.size - 1].written < SLOTS_PER_CHUNK) return Tail.Room
        if (spare.isNotEmpty()) {
            filling.add(spare.removeAt(spare.size - 1))
            return Tail.Room
        }
        if (made == maxChunks) return Tail.Full
        return when (val grown = chunks.make()) {
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
