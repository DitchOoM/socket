package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.PeerTransportParams
import com.ditchoom.socket.quic.core.observe.Confined

/**
 * The server transport parameters a replay of rejected 0-RTT is judged on (RFC 9000 §7.4.1, RFC 9221 §3), as a ticket remembers them or a
 * handshake grants them. A parameter the server did not send is its default, 0; [datagramFrameSize] 0 is no datagrams.
 */
internal class TicketParams(
    val streamsBidi: Long,
    val streamsUni: Long,
    val datagramFrameSize: Long,
) {
    companion object {
        /** What the handshake granted; parameters it has not processed are judged as the ticket's [remembered]. */
        fun granted(
            params: PeerTransportParams,
            remembered: TicketParams,
        ): TicketParams =
            when (params) {
                is PeerTransportParams.NotYetNegotiated -> remembered
                is PeerTransportParams.Negotiated ->
                    TicketParams(
                        params.initialMaxStreamsBidi,
                        params.initialMaxStreamsUni,
                        maxOf(0L, params.maxDatagramFrameSize),
                    )
            }
    }
}

/** A ticket's remembered parameters: read, or bytes that are not quiche's session encoding. */
internal sealed interface TicketRead {
    class Read(
        val params: TicketParams,
    ) : TicketRead

    data object Unreadable : TicketRead
}

/**
 * Reads the parameters quiche's serialized session carries: an 8-byte big-endian length and the TLS session, then an
 * 8-byte length and the server's transport parameters as RFC 9000 §18 encodes them. Reads [serialized] in place.
 */
internal fun ticketParamsOf(serialized: ReadBuffer): TicketRead {
    val reader = ParamReader(serialized)
    reader.skip(reader.fixed())
    val length = reader.fixed()
    val end = reader.at + length
    if (end > reader.limit) return TicketRead.Unreadable
    val values = LongArray(PARAM_IDS.size)
    while (reader.at < end && reader.read == Read.Whole) {
        val id = reader.varInt()
        val size = reader.varInt()
        val valueEnd = reader.at + size
        val slot = PARAM_IDS.indexOf(id)
        if (slot in values.indices) values[slot] = reader.varInt()
        if (valueEnd > end || reader.at > valueEnd) return TicketRead.Unreadable
        reader.skip(valueEnd - reader.at)
    }
    return when (reader.read) {
        Read.Whole -> TicketRead.Read(TicketParams(values[0], values[1], values[2]))
        Read.PastTheEnd -> TicketRead.Unreadable
    }
}

/** initial_max_streams_bidi and _uni, max_datagram_frame_size. */
private val PARAM_IDS = longArrayOf(0x08, 0x09, 0x20)

/** Whether every read stayed inside the bytes. */
private enum class Read { Whole, PastTheEnd }

/** Reads a serialized session from its first byte; a read past the end answers 0 and is remembered in [read]. */
@Confined
private class ParamReader(
    private val bytes: ReadBuffer,
) {
    val limit = bytes.limit().toLong()
    var at = bytes.position().toLong()
        private set
    var read = Read.Whole
        private set

    fun skip(count: Long) {
        if (count < 0 || count > limit - at) read = Read.PastTheEnd else at += count
    }

    /** An 8-byte big-endian length; one past a Long is past every end. */
    fun fixed(): Long {
        var value = 0L
        repeat(FIXED_BYTES) { value = (value shl BYTE_BITS) or byteAt() }
        if (value < 0) read = Read.PastTheEnd
        return if (value < 0) 0L else value
    }

    /** An RFC 9000 §16 variable-length integer. */
    fun varInt(): Long {
        val first = byteAt()
        var value = first and VARINT_FIRST_MASK
        repeat((1 shl (first.toInt() ushr VARINT_PREFIX_SHIFT)) - 1) { value = (value shl BYTE_BITS) or byteAt() }
        return value
    }

    private fun byteAt(): Long {
        if (at >= limit) {
            read = Read.PastTheEnd
            return 0L
        }
        return bytes[(at++).toInt()].toLong() and BYTE_MASK
    }

    private companion object {
        const val FIXED_BYTES = 8
        const val BYTE_BITS = 8
        const val BYTE_MASK = 0xFFL
        const val VARINT_PREFIX_SHIFT = 6
        const val VARINT_FIRST_MASK = 0x3FL
    }
}
