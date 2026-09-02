package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for #563: a server creates a connection for a fully conforming Initial and for
 * nothing else.
 *
 * RFC 9000 §5.2.2: a packet whose connection ID matches no connection is processed only if it is an
 * Initial; "Servers MUST drop incoming packets under all other circumstances." §14.1: "A server MUST
 * discard an Initial packet that is carried in a UDP datagram with a payload that is smaller than
 * the smallest allowed maximum datagram size of 1200 bytes." `quiche_accept` checks neither, and
 * before #563 the receive loop never read the packet type it was handed, so a stray short-header
 * packet, a Handshake packet or a 300-byte Initial each became a quiche connection and a driver that
 * lived until the idle timeout.
 *
 * ## How it is observed
 *
 * Through the server-level trace record (#562): the receive loop records every datagram it dequeues
 * and cannot route as a typed `ERROR`, and the accept-time Initial of every connection it creates as
 * a `DGRAM_IN`. So "dropped for the right reason" and "no connection was created" are both readable
 * off one sink, deterministically, instead of asserting that a handler *did not* run within some
 * wait. Each crafted datagram is sent from a plain UDP socket, so no client-side QUIC state exists
 * to be confused with the server's.
 *
 * The positive control is the real thing: a `withQuicConnection` against the same server still
 * establishes, and its Initial is the one accept-time `DGRAM_IN` on the sink.
 */
class ServerAcceptsOnlyInitialsTests {
    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 5.seconds,
        )

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    /** Every event the server recorded, in order, plus the drops by type name. */
    private class ServerRecord {
        val events: MutableList<TraceEvent> = Collections.synchronizedList(mutableListOf())
        val sink = TraceSink { events += it }

        fun drops(): List<TraceEvent.Error> =
            synchronized(events) { events.filterIsInstance<TraceEvent.Error>().filter { "ServerDatagramDrop" in it.type } }

        fun acceptTimeInitials(): Int = synchronized(events) { events.count { it is TraceEvent.DgramIn } }

        fun connectionStates(): Int = synchronized(events) { events.count { it is TraceEvent.State } }
    }

    /**
     * A short-header packet (fixed bit set, header-form bit clear) whose DCID routes nowhere: the
     * shape of a stale 1-RTT packet arriving after its connection is gone.
     */
    @Test
    fun aShortHeaderPacketForNoConnectionIsDroppedNotAccepted() =
        assertDropped(shortHeaderPacket(), expectedDrop = "NotAnInitial", expectedType = PACKET_TYPE_SHORT)

    /** A Handshake-type long header: a client cannot send one before a server response (§5.2.2). */
    @Test
    fun aHandshakePacketForNoConnectionIsDroppedNotAccepted() =
        assertDropped(longHeaderPacket(typeBits = 0b10, size = 1200), expectedDrop = "NotAnInitial", expectedType = PACKET_TYPE_HANDSHAKE)

    /** An Initial in a 300-byte datagram: parseable, well-formed, and below the §14.1 floor. */
    @Test
    fun aRuntInitialIsDroppedNotAccepted() =
        assertDropped(longHeaderPacket(typeBits = 0b00, size = 300), expectedDrop = "RuntInitial", expectedType = null)

    @Test
    fun aRealClientStillEstablishesAndItsInitialIsTheOneAcceptTimeDatagram() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(ServerAcceptsOnlyInitialsTests::class) {
                val record = ServerRecord()
                withTimeout(30.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options.copy(trace = QuicTraceCapture(record.sink))) {
                        val acceptJob = launch { runCatching { connections { } } }
                        try {
                            withQuicConnection("127.0.0.1", port, options, timeout = 10.seconds) { }
                        } finally {
                            acceptJob.cancel()
                        }
                    }
                }
                assertTrue(record.acceptTimeInitials() >= 1, "the real client's Initial must be recorded at accept: ${record.events}")
                assertTrue(record.connectionStates() >= 1, "the real client must have produced a connection: ${record.events}")
                val unexpected = record.drops().filter { "NotAnInitial" in it.type || "RuntInitial" in it.type }
                assertEquals(emptyList(), unexpected, "a conforming handshake must not trip the #563 checks")
            }
        }

    private fun assertDropped(
        datagram: ByteArray,
        expectedDrop: String,
        expectedType: Int?,
    ) = runBlocking(Dispatchers.IO) {
        skipOnMissingNativeLib(ServerAcceptsOnlyInitialsTests::class) {
            val record = ServerRecord()
            withTimeout(30.seconds) {
                withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options.copy(trace = QuicTraceCapture(record.sink))) {
                    val acceptJob = launch { runCatching { connections { } } }
                    try {
                        val probe = UdpSocket.connect("127.0.0.1", port, receiveBufferSize = 2048)
                        try {
                            val payload = BufferFactory.deterministic().allocate(datagram.size)
                            datagram.forEach { payload.writeByte(it) }
                            payload.resetForRead()
                            probe.send(payload)
                        } finally {
                            probe.close()
                        }
                        // The drop is recorded on the receive loop, synchronously with the dequeue, so
                        // it appears within one scheduling of that loop; the bound is only a backstop.
                        val drop =
                            withTimeoutOrNull(DROP_WAIT) {
                                while (record.drops().isEmpty()) delay(POLL)
                                record.drops().single()
                            } ?: fail(
                                "the server recorded no drop for a ${datagram.size}-byte $expectedDrop-shaped datagram " +
                                    "(#563): it was either accepted as a connection or silently discarded. " +
                                    "Recorded: ${record.events}",
                            )
                        assertTrue(
                            expectedDrop in drop.type,
                            "expected the drop to be typed $expectedDrop, got ${drop.type}: ${drop.message}",
                        )
                        if (expectedType != null) {
                            assertTrue(
                                "packet type $expectedType " in drop.message,
                                "the drop must name quiche's packet type $expectedType: ${drop.message}",
                            )
                        }
                        assertEquals(0, record.acceptTimeInitials(), "no accept-time datagram may be recorded: ${record.events}")
                        assertEquals(0, record.connectionStates(), "no connection may have been created: ${record.events}")
                    } finally {
                        acceptJob.cancel()
                    }
                }
            }
        }
    }

    /** `0x40` fixed bit, then 20 bytes of DCID (the server parses `dcil = 20`), then a few more. */
    private fun shortHeaderPacket(): ByteArray = ByteArray(40) { if (it == 0) 0x40 else 0x2a }

    /**
     * A version-1 long header of the given type ([typeBits] in bits 5–4: 00 Initial, 10 Handshake),
     * 8-byte DCID and SCID, a zero-length token for an Initial, and zero padding to [size] bytes.
     * `quiche_header_info` parses through the SCID (and the token for an Initial) and no further, so
     * the payload after that is never inspected.
     */
    private fun longHeaderPacket(
        typeBits: Int,
        size: Int,
    ): ByteArray {
        val out = ByteArray(size)
        var i = 0
        out[i++] = (0xC0 or (typeBits shl 4)).toByte() // form=1, fixed=1, type
        out[i++] = 0
        out[i++] = 0
        out[i++] = 0
        out[i++] = 1 // version 1
        out[i++] = 8
        repeat(8) { out[i++] = 0x11 } // DCID
        out[i++] = 8
        repeat(8) { out[i++] = 0x22 } // SCID
        if (typeBits == 0) out[i++] = 0 // Initial: token length 0
        return out
    }

    private companion object {
        /** quiche_header_info's numbering (quiche/src/ffi.rs). */
        const val PACKET_TYPE_HANDSHAKE = 3
        const val PACKET_TYPE_SHORT = 5

        val DROP_WAIT = 5.seconds
        val POLL = 10.milliseconds
    }
}
