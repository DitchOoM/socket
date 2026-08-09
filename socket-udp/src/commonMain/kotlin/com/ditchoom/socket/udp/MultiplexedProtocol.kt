@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlin.jvm.JvmInline

/**
 * The protocols that can share one UDP port under the RFC 7983 / RFC 9443 multiplexing scheme — the
 * ICE/WebRTC family plus QUIC — told apart by the first byte of each datagram.
 *
 * This is what makes "one port for everything" possible: a media/ICE agent and a QUIC stack cannot
 * each own the socket, so one reader classifies every datagram and hands it to the right stack.
 * Classification is a *pure function of one byte* (plus, for one range, the source address), so a
 * sans-I/O stack can use it on datagrams it already holds, with no socket in sight.
 *
 * Sealed rather than an enum because the cases are not interchangeable: [NonQuic] is the subset a
 * shared-port consumer can actually be handed, and typing a delivery as [NonQuic] makes "what do I
 * do with a QUIC packet here?" a question that cannot be asked — the QUIC branch already took it.
 */
sealed interface MultiplexedProtocol {
    /**
     * The protocols delivered to the shared-port consumer — everything a QUIC stack does not claim.
     * A `when` over this type is exhaustive across exactly the cases that can arrive, so adding a
     * protocol to the scheme later breaks consumers at compile time instead of at runtime.
     */
    sealed interface NonQuic : MultiplexedProtocol

    /** STUN (RFC 8489) — ICE connectivity checks and TURN control messages. */
    data object Stun : NonQuic

    /** ZRTP (RFC 6189) media-path key agreement. */
    data object Zrtp : NonQuic

    /** DTLS (RFC 9147), including the DTLS-SRTP key negotiation. */
    data object Dtls : NonQuic

    /** A TURN ChannelData message (RFC 8656 §12) — only ever from a relay we allocated on. */
    data object TurnChannel : NonQuic

    /** RTP or RTCP (RFC 3550), including SRTP/SRTCP. */
    data object RtpRtcp : NonQuic

    /** QUIC (RFC 9000) — a long header, or a short header with the fixed ("QUIC") bit set. */
    data object Quic : MultiplexedProtocol

    /**
     * Belongs to no protocol on this port and must be dropped: a first byte in the range RFC 9443
     * leaves unassigned (4..15), or a zero-length datagram, which is legal UDP and has no first byte
     * to classify at all. Forwarding either one anywhere is a guess, and a guess on a shared port is
     * how one stack corrupts another's state.
     */
    data object Unroutable : MultiplexedProtocol
}

/**
 * Classify a datagram by its [firstByte] per the RFC 9443 §3 table (which updates RFC 7983 to add
 * QUIC), for an endpoint with **no TURN allocation**:
 *
 * ```
 *   0..3    STUN            80..127   QUIC (short header)
 *   4..15   drop           128..191   RTP/RTCP
 *  16..19   ZRTP           192..255   QUIC (long header)
 *  20..63   DTLS
 *  64..79   QUIC  (TURN ChannelData only when relaying — see [TurnRelays])
 * ```
 *
 * The ranges are disjoint because each protocol constrains its leading bits: QUIC sets the fixed bit
 * (0x40) in every packet, DTLS uses content types 20..63, RTP/RTCP carry version 2 in the top bits,
 * and STUN's first two bits are zero.
 *
 * **The one obligation on a QUIC endpoint sharing a port**: RFC 9443 §3 requires that it not send
 * the `grease_quic_bit` transport parameter (RFC 9287), because a peer thereby permitted to grease
 * the fixed bit emits short-header packets whose first byte lands in the DTLS/STUN ranges. On a
 * shared socket the QUIC binding enforces this for you rather than trusting a doc comment — see
 * `QuicPortBinding.Shared`.
 */
fun classifyMultiplexedDatagram(firstByte: Byte): MultiplexedProtocol =
    when (val b = firstByte.toInt() and 0xFF) {
        in 0..3 -> MultiplexedProtocol.Stun
        in 4..15 -> MultiplexedProtocol.Unroutable
        in 16..19 -> MultiplexedProtocol.Zrtp
        in 20..63 -> MultiplexedProtocol.Dtls
        in 64..127 -> MultiplexedProtocol.Quic
        in 128..191 -> MultiplexedProtocol.RtpRtcp
        else -> {
            check(b in 192..255) { "unreachable: byte $b outside 0..255" }
            MultiplexedProtocol.Quic
        }
    }

/**
 * Classify this payload without consuming it: peeks the byte at the buffer's current position and
 * restores the position, so the buffer can then be handed to the stack that owns it (ownership
 * transfer, no copy). An empty payload is [MultiplexedProtocol.Unroutable].
 */
fun ReadBuffer.classifyMultiplexedDatagram(): MultiplexedProtocol =
    peekFirstByte()?.let { classifyMultiplexedDatagram(it) } ?: MultiplexedProtocol.Unroutable

/**
 * The TURN relays this endpoint has an allocation on — the *source-based* half of RFC 9443's
 * demultiplexing rule, and the only genuine ambiguity in the table.
 *
 * TURN channel numbers 0x4000..0x4FFF put a ChannelData message's first byte in exactly the QUIC
 * short-header range (64..79). No amount of looking at the bytes separates them; RFC 9443 §3 settles
 * it by *source*, because ChannelData can only arrive from a server we have sent an allocation and
 * channel-binding request to. So the disambiguation is this set — not a flag a caller has to
 * remember the meaning of, and not a nullable "maybe there's a relay".
 *
 * An endpoint that never relays uses [None] (or the top-level [classifyMultiplexedDatagram], which
 * needs no source address at all): with no relays, 64..79 is unambiguously QUIC.
 */
@JvmInline
value class TurnRelays(
    val addresses: Set<SocketAddress>,
) {
    /**
     * Classify a datagram with [firstByte] received [from] a peer. Identical to the top-level
     * [classifyMultiplexedDatagram] except in 64..79, which reads as [MultiplexedProtocol.TurnChannel]
     * when — and only when — [from] is one of these relays.
     */
    fun classify(
        firstByte: Byte,
        from: SocketAddress,
    ): MultiplexedProtocol {
        val byPattern = classifyMultiplexedDatagram(firstByte)
        val ambiguous = (firstByte.toInt() and 0xFF) in TURN_CHANNEL_RANGE
        return if (ambiguous && from in addresses) MultiplexedProtocol.TurnChannel else byPattern
    }

    /** Non-consuming [classify] over a payload buffer; an empty payload is [MultiplexedProtocol.Unroutable]. */
    fun classify(
        payload: ReadBuffer,
        from: SocketAddress,
    ): MultiplexedProtocol = payload.peekFirstByte()?.let { classify(it, from) } ?: MultiplexedProtocol.Unroutable

    companion object {
        /** No TURN allocation: 64..79 is QUIC, whoever it came from. */
        val None = TurnRelays(emptySet())

        /** The first bytes TURN ChannelData and QUIC short headers both claim (RFC 9443 §3). */
        private val TURN_CHANNEL_RANGE = 64..79
    }
}

/** The byte a classification reads, or null for a zero-length payload. Never moves the cursor. */
private fun ReadBuffer.peekFirstByte(): Byte? {
    if (remaining() < 1) return null
    val start = position()
    val firstByte = readByte()
    position(start)
    return firstByte
}
