package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN

/** What the version-independent part of a datagram's first packet says it is (RFC 8999 section 5.1). */
internal sealed interface Wire {
    /** A short header: its destination id is as long as the ids this endpoint issues. */
    object Short : Wire

    /** A version 1 Initial. */
    object InitialV1 : Wire

    /** A version 1 long header that is not an Initial. */
    object OtherV1 : Wire

    /** A long header naming a version this endpoint does not speak, and so owed a Version Negotiation. */
    object UnsupportedVersion : Wire

    /** A Version Negotiation packet, or a datagram too short or malformed to route. */
    object Unroutable : Wire
}

/**
 * Reads the destination connection id of a datagram from its invariant header, without a native call. Loop thread
 * only: every field is overwritten for each datagram.
 */
internal class PacketHeader {
    /** The destination id of the datagram last read, unless it was [Wire.Unroutable] or [Wire.UnsupportedVersion]. */
    val cid = CidRead()

    /** Where the destination id of a [Wire.UnsupportedVersion] packet is, and how long. */
    var dcidOffset = 0
        private set
    var dcidLength = 0
        private set

    /** Where the source id of a long header is, and how long. */
    var scidOffset = 0
        private set
    var scidLength = 0
        private set

    /** Classifies the first [length] bytes of [datagram] and, for a routable one, leaves its destination id in [cid]. */
    fun read(
        datagram: ReadBuffer,
        length: Int,
    ): Wire {
        if (length < 1) return Wire.Unroutable
        val first = datagram[0].toInt() and 0xFF
        if (first and LONG_FORM == 0) {
            if (length < 1 + QUIC_ISSUED_CONN_ID_LEN) return Wire.Unroutable
            cid.read(datagram, 1, QUIC_ISSUED_CONN_ID_LEN)
            return Wire.Short
        }
        if (length < MIN_LONG) return Wire.Unroutable
        var version = 0L
        for (i in 1..4) version = (version shl 8) or (datagram[i].toLong() and 0xFF)
        dcidOffset = 6
        dcidLength = datagram[5].toInt() and 0xFF
        val scidLengthAt = dcidOffset + dcidLength
        if (scidLengthAt >= length) return Wire.Unroutable
        scidLength = datagram[scidLengthAt].toInt() and 0xFF
        scidOffset = scidLengthAt + 1
        if (scidOffset + scidLength > length) return Wire.Unroutable
        return when {
            version == 0L -> Wire.Unroutable
            // Version-specific limits on id length must not decide a Version Negotiation (RFC 9000 section 17.2.1).
            version != QUIC_V1 -> Wire.UnsupportedVersion
            dcidLength > CidRead.MAX_CID_LENGTH -> Wire.Unroutable
            else -> {
                cid.read(datagram, dcidOffset, dcidLength)
                if ((first ushr TYPE_SHIFT) and TYPE_MASK == INITIAL_TYPE) Wire.InitialV1 else Wire.OtherV1
            }
        }
    }

    private companion object {
        const val LONG_FORM = 0x80
        const val MIN_LONG = 7
        const val TYPE_SHIFT = 4
        const val TYPE_MASK = 3
        const val INITIAL_TYPE = 0
        const val QUIC_V1 = 1L
    }
}
