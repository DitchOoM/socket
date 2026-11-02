package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer

/**
 * QUIC version 1, the one version quiche speaks: its `version_is_supported` matches exactly this value
 * and `quiche_negotiate_version` advertises exactly this value.
 */
internal const val QUIC_VERSION_1 = 0x0000_0001L

/**
 * What a datagram's first packet says about its version, read through the version-independent
 * header of RFC 8999 only.
 *
 * Read before `quiche_header_info`, which parses a version 1 header and applies version 1's rules
 * (packet-type bits, at most 20-byte connection IDs, a token for an Initial). For a version this
 * server does not speak those fields mean nothing, and RFC 9000 §17.2.1 forbids using them:
 * "Version-specific rules for the connection ID therefore MUST NOT influence a decision about
 * whether to send a Version Negotiation packet."
 */
internal sealed interface VersionInvariantHeader {
    /**
     * A version 1 long header, a Version Negotiation packet (version 0), a short header (no version
     * field), or a long header too short to carry the invariant fields. Each goes to the version 1
     * path, which routes it, accepts it, or records why it did neither.
     */
    data object ForVersion1Path : VersionInvariantHeader

    /**
     * A long header naming a version this server does not support. The connection IDs are given as
     * offsets into the datagram, so a Version Negotiation reply can echo them without copying.
     */
    data class UnsupportedVersion(
        val version: Long,
        val dcidOffset: Int,
        val dcidLen: Int,
        val scidOffset: Int,
        val scidLen: Int,
    ) : VersionInvariantHeader

    companion object {
        /**
         * Read the invariant header of the [len]-byte datagram in [buffer], indexed from 0.
         *
         * RFC 8999 §5.1: header form (bit 7 of byte 0), Version (32), DCID length (8), DCID
         * (0..2040), SCID length (8), SCID (0..2040).
         */
        fun of(
            buffer: ReadBuffer,
            len: Int,
        ): VersionInvariantHeader {
            if (len < 7) return ForVersion1Path
            val first = buffer[0].toInt() and 0xFF
            if (first and 0x80 == 0) return ForVersion1Path
            var version = 0L
            for (i in 1..4) version = (version shl 8) or (buffer[i].toLong() and 0xFF)
            if (version == 0L || version == QUIC_VERSION_1) return ForVersion1Path
            val dcidLen = buffer[5].toInt() and 0xFF
            val scidLenAt = 6 + dcidLen
            if (scidLenAt >= len) return ForVersion1Path
            val scidLen = buffer[scidLenAt].toInt() and 0xFF
            if (scidLenAt + 1 + scidLen > len) return ForVersion1Path
            return UnsupportedVersion(version, dcidOffset = 6, dcidLen = dcidLen, scidOffset = scidLenAt + 1, scidLen = scidLen)
        }

        /** The largest Version Negotiation packet quiche writes: one 255-byte CID each way and one version. */
        const val MAX_VERSION_NEGOTIATION_LEN = 1 + 4 + 1 + 255 + 1 + 255 + 4
    }
}
