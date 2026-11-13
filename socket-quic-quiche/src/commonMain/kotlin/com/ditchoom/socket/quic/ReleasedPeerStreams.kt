package com.ditchoom.socket.quic

/**
 * The streams the peer opened whose [StreamSlot] the driver has released.
 *
 * A stream the driver has no slot for and quiche reports readable is a stream the peer just opened. A
 * released stream is not that, whatever quiche reports for it, so the driver asks here before announcing
 * a stream. Peer-opened streams of one kind are numbered in order, so the set is a floor (every stream
 * below it is released) and the few released streams above it: it stays as small as the number of streams
 * the peer has open or has left unused, however many it has carried. Loop-confined, like the stream map.
 */
internal class ReleasedPeerStreams {
    private val bidirectional = Released()
    private val unidirectional = Released()

    /** [id], a peer-opened stream, is released. */
    fun add(id: QuicStreamId) = kind(id).add(id.id shr KIND_BITS)

    operator fun contains(id: QuicStreamId): Boolean = kind(id).contains(id.id shr KIND_BITS)

    private fun kind(id: QuicStreamId): Released = if (id.isBidirectional) bidirectional else unidirectional

    private class Released {
        private var floor = 0L
        private val above = HashSet<Long>()

        fun add(index: Long) {
            if (index < floor) return
            above += index
            while (above.remove(floor)) floor++
        }

        fun contains(index: Long): Boolean = index < floor || index in above
    }

    private companion object {
        /** The two low bits of a stream id say who opened it and in which directions; the rest is its number. */
        const val KIND_BITS = 2
    }
}
