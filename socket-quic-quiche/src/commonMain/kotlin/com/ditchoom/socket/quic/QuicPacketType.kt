package com.ditchoom.socket.quic

/**
 * The packet type `quiche_header_info` reports for a datagram's first packet, decoded at the FFI
 * boundary from quiche's own numbering — Initial = 1, Retry = 2, Handshake = 3, 0-RTT = 4, Short = 5,
 * Version Negotiation = 6 (`quiche/src/ffi.rs`, `quiche_header_info`; the C header declares no names
 * for them) — so nothing above the boundary compares a raw byte against a magic number.
 *
 * Sealed rather than an enum because the FFI can, in principle, hand back a value this build does
 * not know: that is a state, [Unknown], with the byte kept, not an exception and not a silent
 * coercion onto a neighbour.
 */
internal sealed interface QuicPacketType {
    data object Initial : QuicPacketType

    data object Retry : QuicPacketType

    data object Handshake : QuicPacketType

    data object ZeroRtt : QuicPacketType

    data object Short : QuicPacketType

    data object VersionNegotiation : QuicPacketType

    /** A numbering this build does not know. [raw] is the byte quiche wrote. */
    data class Unknown(
        val raw: Int,
    ) : QuicPacketType

    companion object {
        /** Decode the byte `quiche_header_info` wrote into its `type` out-parameter. */
        fun fromQuiche(raw: Int): QuicPacketType =
            when (raw) {
                1 -> Initial
                2 -> Retry
                3 -> Handshake
                4 -> ZeroRtt
                5 -> Short
                6 -> VersionNegotiation
                else -> Unknown(raw)
            }
    }
}
