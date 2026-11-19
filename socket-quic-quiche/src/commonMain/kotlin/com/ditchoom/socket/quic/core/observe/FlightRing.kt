package com.ditchoom.socket.quic.core.observe

/**
 * A connection's flight recorder: the last [capacity] records of what crossed its edges, in preallocated words, so
 * recording allocates nothing. A record is four longs:
 *
 * ```
 * w0  loop nanos
 * w1  kind(8) | sub(8) | local address id(16) | peer address id(16) | length(16)
 * w2  a
 * w3  b
 * ```
 *
 * Addresses are recorded once in a table beside the ring and referred to by id, so a datagram's record names its
 * 4-tuple and a path change shows as a change of ids. The oldest record is overwritten when the ring is full; the
 * address table is not.
 */
@Confined
internal class FlightRing(
    val capacity: Int,
) {
    private val words = LongArray(capacity * WORDS)
    private val addresses = LongArray(MAX_ADDRESSES * ADDRESS_WORDS)
    private var next = 0
    private var written = 0L
    private var bound = 0
    private var victim = 0

    /** Records held now. */
    val size: Int get() = if (written < capacity) written.toInt() else capacity

    /** Records overwritten because the ring was full. */
    val dropped: Long get() = written - size

    val addressCount: Int get() = bound

    /** The id of this address, recording it on first sight. A full table reuses its oldest id. */
    fun bindAddress(
        family: Int,
        port: Int,
        hi: Long,
        lo: Long,
    ): Int {
        val head = (family.toLong() shl PORT_BITS) or (port.toLong() and PORT_MASK)
        for (id in 0 until bound) {
            val at = id * ADDRESS_WORDS
            if (addresses[at] == head && addresses[at + 1] == hi && addresses[at + 2] == lo) return id
        }
        val id = if (bound < MAX_ADDRESSES) bound++ else victim.also { victim = (victim + 1) % MAX_ADDRESSES }
        restoreAddress(id, head, hi, lo)
        return id
    }

    internal fun restoreAddress(
        id: Int,
        head: Long,
        hi: Long,
        lo: Long,
    ) {
        val at = id * ADDRESS_WORDS
        addresses[at] = head
        addresses[at + 1] = hi
        addresses[at + 2] = lo
        if (id >= bound) bound = id + 1
    }

    fun familyOf(id: Int): Int = (addresses[id * ADDRESS_WORDS] shr PORT_BITS).toInt()

    fun portOf(id: Int): Int = (addresses[id * ADDRESS_WORDS] and PORT_MASK).toInt()

    fun hiOf(id: Int): Long = addresses[id * ADDRESS_WORDS + 1]

    fun loOf(id: Int): Long = addresses[id * ADDRESS_WORDS + 2]

    /** A datagram that arrived or left: [tag] is its last 8 bytes (an AEAD tag), [head] its first 8. */
    fun datagram(
        kind: FlightKind,
        nanos: Long,
        length: Int,
        local: Int,
        peer: Int,
        tag: Long,
        head: Long,
    ) = put(kind, nanos, 0, local, peer, length, tag, head)

    fun timer(
        kind: FlightKind,
        nanos: Long,
        dueNanos: Long,
    ) = put(kind, nanos, 0, 0, 0, 0, dueNanos, 0L)

    fun op(
        kind: FlightKind,
        nanos: Long,
        op: Int,
        streamId: Long,
        length: Long,
    ) = put(kind, nanos, op, 0, 0, 0, streamId, length)

    fun event(
        kind: FlightKind,
        nanos: Long,
        sub: Int,
        local: Int,
        peer: Int,
        a: Long,
        b: Long,
    ) = put(kind, nanos, sub, local, peer, 0, a, b)

    fun ended(
        nanos: Long,
        end: Int,
        errorCode: Long,
    ) = put(FlightKind.ConnEnd, nanos, end, 0, 0, 0, errorCode, 0L)

    /** Appends a record; every typed method above is this with its fields placed. */
    fun put(
        kind: FlightKind,
        nanos: Long,
        sub: Int,
        local: Int,
        peer: Int,
        length: Int,
        a: Long,
        b: Long,
    ) {
        val at = next * WORDS
        words[at] = nanos
        words[at + 1] = pack(kind.ordinal, sub, local, peer, length)
        words[at + 2] = a
        words[at + 3] = b
        next = if (next + 1 == capacity) 0 else next + 1
        written++
    }

    // Record i counts from the oldest held record.
    private fun at(i: Int): Int = (if (written < capacity) i else (next + i) % capacity) * WORDS

    fun nanosAt(i: Int): Long = words[at(i)]

    fun kindAt(i: Int): FlightKind = FlightKind.entries[(words[at(i) + 1] ushr KIND_SHIFT).toInt() and BYTE]

    fun subAt(i: Int): Int = (words[at(i) + 1] ushr SUB_SHIFT).toInt() and BYTE

    fun localAt(i: Int): Int = (words[at(i) + 1] ushr LOCAL_SHIFT).toInt() and SHORT

    fun peerAt(i: Int): Int = (words[at(i) + 1] ushr PEER_SHIFT).toInt() and SHORT

    fun lengthAt(i: Int): Int = words[at(i) + 1].toInt() and SHORT

    fun aAt(i: Int): Long = words[at(i) + 2]

    fun bAt(i: Int): Long = words[at(i) + 3]

    private fun pack(
        kind: Int,
        sub: Int,
        local: Int,
        peer: Int,
        length: Int,
    ): Long =
        (kind.toLong() shl KIND_SHIFT) or ((sub.toLong() and BYTE_MASK) shl SUB_SHIFT) or
            ((local.toLong() and SHORT_MASK) shl LOCAL_SHIFT) or ((peer.toLong() and SHORT_MASK) shl PEER_SHIFT) or
            (length.toLong() and SHORT_MASK)

    internal companion object {
        const val MAX_ADDRESSES = 64
        const val PORT_BITS = 32
        private const val WORDS = 4
        private const val ADDRESS_WORDS = 3
        private const val PORT_MASK = 0xFFFFFFFFL
        private const val KIND_SHIFT = 56
        private const val SUB_SHIFT = 48
        private const val LOCAL_SHIFT = 32
        private const val PEER_SHIFT = 16
        private const val BYTE = 0xFF
        private const val SHORT = 0xFFFF
        private const val BYTE_MASK = 0xFFL
        private const val SHORT_MASK = 0xFFFFL
    }
}
