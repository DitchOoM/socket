package com.ditchoom.socket.quic.core.observe

/** What [FlightCodec.read] made of a recording. */
internal sealed interface Recording {
    class Restored(
        val ring: FlightRing,
    ) : Recording

    /** The bytes do not start with the format's marker. */
    data object NotARecording : Recording

    /** The bytes ended inside a record. */
    data object Truncated : Recording
}

/**
 * The compact binary form of a [FlightRing], oldest record first:
 *
 * ```
 * "QFR" version(1)  addresses: count, then per address family(1) port(varint) hi(8) lo(8)
 * dropped(varint)   records:   count, then per record
 *   flags|kind(1)  sub(1)  nanos delta (zigzag varint)  local, peer, length (varint)  [a(8)] [b(8)]
 * ```
 *
 * `flags` is bit 7 when `a` is present and bit 6 when `b` is; absent fields are zero. Longs are big-endian.
 */
internal object FlightCodec {
    fun write(
        ring: FlightRing,
        sink: FlightSink,
    ) {
        sink.put(MARK_Q)
        sink.put(MARK_F)
        sink.put(MARK_R)
        sink.put(VERSION)
        varint(sink, ring.addressCount.toLong())
        for (id in 0 until ring.addressCount) {
            sink.put(ring.familyOf(id).toByte())
            varint(sink, ring.portOf(id).toLong())
            fixed(sink, ring.hiOf(id))
            fixed(sink, ring.loOf(id))
        }
        varint(sink, ring.dropped)
        varint(sink, ring.size.toLong())
        var previous = 0L
        for (i in 0 until ring.size) {
            val a = ring.aAt(i)
            val b = ring.bAt(i)
            val flags = (if (a != 0L) HAS_A else 0) or (if (b != 0L) HAS_B else 0)
            sink.put((flags or ring.kindAt(i).ordinal).toByte())
            sink.put(ring.subAt(i).toByte())
            val nanos = ring.nanosAt(i)
            varint(sink, zigzag(nanos - previous))
            previous = nanos
            varint(sink, ring.localAt(i).toLong())
            varint(sink, ring.peerAt(i).toLong())
            varint(sink, ring.lengthAt(i).toLong())
            if (a != 0L) fixed(sink, a)
            if (b != 0L) fixed(sink, b)
        }
    }

    fun read(source: FlightSource): Recording {
        if (!source.hasMore()) return Recording.Truncated
        if (!isMarked(source)) return Recording.NotARecording
        if (!source.hasMore() || source.next() != VERSION) return Recording.NotARecording
        val reader = Reader(source)
        val addresses = reader.varint().toInt()
        val heads = LongArray(addresses)
        val his = LongArray(addresses)
        val los = LongArray(addresses)
        for (id in 0 until addresses) {
            val family = reader.byte()
            heads[id] = (family.toLong() shl FlightRing.PORT_BITS) or reader.varint()
            his[id] = reader.fixed()
            los[id] = reader.fixed()
        }
        reader.varint()
        val count = reader.varint().toInt()
        if (reader.truncated) return Recording.Truncated
        val ring = FlightRing(maxOf(count, 1))
        for (id in 0 until addresses) ring.restoreAddress(id, heads[id], his[id], los[id])
        var nanos = 0L
        repeat(count) {
            val head = reader.byte()
            val sub = reader.byte()
            nanos += unzigzag(reader.varint())
            val local = reader.varint().toInt()
            val peer = reader.varint().toInt()
            val length = reader.varint().toInt()
            val a = if (head and HAS_A != 0) reader.fixed() else 0L
            val b = if (head and HAS_B != 0) reader.fixed() else 0L
            if (reader.truncated) return Recording.Truncated
            ring.put(FlightKind.entries[head and KIND_MASK], nanos, sub, local, peer, length, a, b)
        }
        return Recording.Restored(ring)
    }

    @Confined
    private class Reader(
        private val source: FlightSource,
    ) {
        private var missing = 0

        /** Whether a read ran past the end of the source. */
        val truncated: Boolean get() = missing > 0

        fun byte(): Int {
            if (!source.hasMore()) {
                missing++
                return 0
            }
            return source.next().toInt() and BYTE
        }

        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                val b = byte()
                value = value or ((b and PAYLOAD).toLong() shl shift)
                if (b and CONTINUE == 0 || truncated) return value
                shift += PAYLOAD_BITS
            }
        }

        fun fixed(): Long {
            var value = 0L
            repeat(LONG_BYTES) { value = (value shl BYTE_BITS) or byte().toLong() }
            return value
        }
    }

    private fun varint(
        sink: FlightSink,
        value: Long,
    ) {
        var rest = value
        while (rest ushr PAYLOAD_BITS != 0L) {
            sink.put(((rest and PAYLOAD.toLong()) or CONTINUE.toLong()).toByte())
            rest = rest ushr PAYLOAD_BITS
        }
        sink.put(rest.toByte())
    }

    private fun fixed(
        sink: FlightSink,
        value: Long,
    ) {
        for (shift in LONG_BYTES - 1 downTo 0) sink.put((value ushr (shift * BYTE_BITS)).toByte())
    }

    private fun isMarked(source: FlightSource): Boolean =
        source.hasMore() &&
            source.next() == MARK_Q &&
            source.hasMore() &&
            source.next() == MARK_F &&
            source.hasMore() &&
            source.next() == MARK_R

    private fun zigzag(value: Long): Long = (value shl 1) xor (value shr LAST_BIT)

    private fun unzigzag(value: Long): Long = (value ushr 1) xor -(value and 1L)

    private const val MARK_Q: Byte = 0x51
    private const val MARK_F: Byte = 0x46
    private const val MARK_R: Byte = 0x52
    private const val VERSION: Byte = 1
    private const val HAS_A = 0x80
    private const val HAS_B = 0x40
    private const val KIND_MASK = 0x3F
    private const val BYTE = 0xFF
    private const val BYTE_BITS = 8
    private const val LONG_BYTES = 8
    private const val PAYLOAD = 0x7F
    private const val PAYLOAD_BITS = 7
    private const val CONTINUE = 0x80
    private const val LAST_BIT = 63
}
