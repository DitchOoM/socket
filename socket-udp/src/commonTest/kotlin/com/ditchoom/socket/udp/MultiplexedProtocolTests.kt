@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The RFC 9443 §3 demultiplexing table, byte by byte. The table is the contract that lets QUIC and
 * the WebRTC family (STUN/ICE, DTLS, SRTP, TURN) share one UDP port, so it is asserted exhaustively
 * over all 256 first-byte values rather than by sampling — an off-by-one at a range edge silently
 * routes a peer's packets into the wrong stack, which is the failure this table exists to prevent.
 */
class MultiplexedProtocolTests {
    private val relay = SocketAddress.ofLiteral("192.0.2.10", 3478)
    private val peer = SocketAddress.ofLiteral("192.0.2.20", 51234)

    /** The expected classification of [b] for an endpoint with no TURN allocation. */
    private fun expected(b: Int) =
        when (b) {
            in 0..3 -> MultiplexedProtocol.Stun
            in 4..15 -> MultiplexedProtocol.Unroutable
            in 16..19 -> MultiplexedProtocol.Zrtp
            in 20..63 -> MultiplexedProtocol.Dtls
            in 64..127 -> MultiplexedProtocol.Quic // 64..79 is TURN only when it comes from a relay
            in 128..191 -> MultiplexedProtocol.RtpRtcp
            else -> MultiplexedProtocol.Quic
        }

    @Test
    fun everyFirstByteClassifiesPerRfc9443() {
        for (b in 0..255) {
            assertEquals(
                expected(b),
                classifyMultiplexedDatagram(b.toByte()),
                "first byte $b (0x${b.toString(16)})",
            )
        }
    }

    /** With relays configured, the table is unchanged everywhere except the one ambiguous range. */
    @Test
    fun relaysChangeOnlyTheAmbiguousRange() {
        val relays = TurnRelays(setOf(relay))
        for (b in 0..255) {
            val fromPeer = relays.classify(b.toByte(), from = peer)
            assertEquals(expected(b), fromPeer, "byte $b from a non-relay peer")

            val fromRelay = relays.classify(b.toByte(), from = relay)
            val expectedFromRelay =
                if (b in 64..79) MultiplexedProtocol.TurnChannel else expected(b)
            assertEquals(expectedFromRelay, fromRelay, "byte $b from the relay")
        }
    }

    /** [TurnRelays.None] is the no-allocation endpoint: 64..79 is QUIC no matter who sent it. */
    @Test
    fun noRelaysMeansTheAmbiguousRangeIsQuic() {
        for (b in 64..79) {
            assertEquals(MultiplexedProtocol.Quic, TurnRelays.None.classify(b.toByte(), from = relay))
            assertEquals(MultiplexedProtocol.Quic, TurnRelays.None.classify(b.toByte(), from = peer))
        }
    }

    /** The bytes real stacks actually put on the wire, as a readable regression net. */
    @Test
    fun realWorldLeadingBytes() {
        // STUN Binding request (RFC 8489: first two bits zero, method 0x0001).
        assertEquals(MultiplexedProtocol.Stun, classifyMultiplexedDatagram(0x00))
        // DTLS handshake record (content type 22) and application data (23).
        assertEquals(MultiplexedProtocol.Dtls, classifyMultiplexedDatagram(22))
        assertEquals(MultiplexedProtocol.Dtls, classifyMultiplexedDatagram(23))
        // RTP version 2, no extensions → 0x80; RTCP sender report → 0x80/0x81.
        assertEquals(MultiplexedProtocol.RtpRtcp, classifyMultiplexedDatagram(0x80.toByte()))
        // QUIC Initial: long header (0x80) + fixed bit (0x40) = 0xC0..0xCF.
        assertEquals(MultiplexedProtocol.Quic, classifyMultiplexedDatagram(0xC0.toByte()))
        // QUIC 1-RTT: short header, fixed bit set, spin/key-phase/pn-len vary over 0x40..0x7F.
        assertEquals(MultiplexedProtocol.Quic, classifyMultiplexedDatagram(0x50.toByte()))
    }

    @Test
    fun bufferOverloadPeeksWithoutConsuming() {
        val buffer = BufferFactory.deterministic().allocate(4)
        buffer.writeByte(0xC3.toByte())
        buffer.writeByte(1)
        buffer.writeByte(2)
        buffer.writeByte(3)
        buffer.resetForRead()

        assertEquals(MultiplexedProtocol.Quic, buffer.classifyMultiplexedDatagram())
        assertEquals(0, buffer.position(), "classification must not consume the first byte")
        assertEquals(4, buffer.remaining(), "the whole payload stays available to the owning stack")
        assertEquals(0xC3.toByte(), buffer.readByte(), "the payload still starts with its own first byte")
    }

    @Test
    fun bufferOverloadRespectsANonZeroStartPosition() {
        val buffer = BufferFactory.deterministic().allocate(2)
        buffer.writeByte(0x00) // a STUN-looking byte the reader has already moved past
        buffer.writeByte(0x16) // DTLS handshake
        buffer.resetForRead()
        buffer.readByte()

        assertEquals(MultiplexedProtocol.Dtls, buffer.classifyMultiplexedDatagram())
        assertEquals(1, buffer.position())
    }

    /** A zero-length datagram is legal UDP and belongs to no protocol — it must not read past the end. */
    @Test
    fun emptyPayloadIsUnroutable() {
        val buffer = BufferFactory.deterministic().allocate(1)
        buffer.resetForRead()
        buffer.setLimit(0)
        assertEquals(MultiplexedProtocol.Unroutable, buffer.classifyMultiplexedDatagram())
        assertEquals(
            MultiplexedProtocol.Unroutable,
            TurnRelays(setOf(relay)).classify(buffer, from = relay),
        )
    }
}
