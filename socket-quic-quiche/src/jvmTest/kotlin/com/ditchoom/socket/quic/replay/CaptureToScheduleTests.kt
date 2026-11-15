package com.ditchoom.socket.quic.replay

import com.ditchoom.socket.quic.QuicRole
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/** [CaptureToSchedule] over a capture small enough to read: three datagrams of ours, two of the peer's. */
class CaptureToScheduleTests {
    private val directory: File = Files.createTempDirectory("capture-to-schedule").toFile().also { it.deleteOnExit() }

    private val hello = datagram(initial(40), padding = 20)
    private val finished = datagram(initial(10) + handshake(30))
    private val request = datagram(handshake(12) + oneRtt(20))
    private val serverHello = datagram(initial(50) + handshake(60))
    private val done = datagram(oneRtt(16))

    /** [request] as the peer received it: a byte of its second packet changed on the way. */
    private val requestDamaged = request.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 0x10).toByte() }

    private fun trace(vararg lines: String): File =
        directory.resolve("ours.trace").also {
            it.writeText(lines.joinToString("\n", postfix = "\n"))
        }

    private fun out(
        micros: Long,
        bytes: ByteArray,
    ) = "v1 ${micros * 1000} DGRAM_OUT ${bytes.size} - ${hex(bytes)}"

    private fun received(
        micros: Long,
        bytes: ByteArray,
    ) = "v1 ${micros * 1000} DGRAM_IN ${bytes.size} - ${hex(bytes)}"

    private fun wholeTrace(): File =
        trace(
            "v1 100000 STATE com.ditchoom.socket.quic.QuicConnectionState.Handshaking",
            out(1_000, hello),
            received(31_500, serverHello),
            out(32_000, finished),
            "v1 32500000 STATE com.ditchoom.socket.quic.QuicConnectionState.Established hq-interop",
            out(33_000, request),
            "v1 900000000 STATE com.ditchoom.socket.quic.QuicConnectionState.Closed local: IdleTimeout (0x-1)",
        )

    /** The link's two captures, on a clock 5 s ahead of the trace's: [finished] lost, [request] damaged, [done] lost. */
    private fun captures(): LinkEvidence.BothEnds {
        val ahead = 5_000_000L
        val oursSide =
            pcap(
                Captured(ahead + 1_000, CLIENT, SERVER, hello),
                Captured(ahead + 31_500, SERVER, CLIENT, serverHello),
                Captured(ahead + 32_000, CLIENT, SERVER, finished),
                Captured(ahead + 33_000, CLIENT, SERVER, request),
            )
        val peerSide =
            pcap(
                Captured(ahead + 16_000, CLIENT, SERVER, hello),
                Captured(ahead + 16_500, SERVER, CLIENT, serverHello),
                Captured(ahead + 48_000, CLIENT, SERVER, requestDamaged),
                Captured(ahead + 48_200, SERVER, CLIENT, done),
            )
        return LinkEvidence.BothEnds(file("left.pcap", oursSide), file("right.pcap", peerSide))
    }

    private fun inputs(
        trace: File,
        link: LinkEvidence,
        qlog: QlogEvidence = QlogEvidence.Absent,
    ) = CaptureInputs("a-small-capture", "a test", QuicRole.Client, trace, link, qlog)

    @Test
    fun aDatagramIsItsCoalescedPacketsAndPaddingIsNotAPacket() {
        assertEquals(listOf(PacketKind.Initial), packetSpans(hello).map { it.kind })
        assertEquals(listOf(PacketKind.Initial, PacketKind.Handshake), packetSpans(serverHello).map { it.kind })
        assertEquals(listOf(PacketKind.Handshake, PacketKind.OneRtt), packetSpans(request).map { it.kind })
        assertEquals(request.size, packetSpans(request).last().until, "a short-header packet runs to the datagram's end")
        assertEquals(emptyList(), packetSpans(byteArrayOf(0xC0.toByte(), 0, 0)).map { it.kind }, "a header cut short is no packet")
    }

    @Test
    fun eachDatagramTakesTheFateTheTwoCapturesOfTheLinkShow() {
        val extracted = assertIs<Extraction.Extracted>(CaptureToSchedule.extract(inputs(wholeTrace(), captures())))
        val schedule = extracted.schedule
        assertEquals(
            listOf(
                Step.OursSent(0.microseconds, listOf(PacketKind.Initial), Fate.Delivered(15_000.microseconds)),
                Step.PeerSent(15_500.microseconds, listOf(PacketKind.Initial, PacketKind.Handshake), Fate.Delivered(30_500.microseconds)),
                Step.OursSent(31_000.microseconds, listOf(PacketKind.Initial, PacketKind.Handshake), Fate.Lost),
                Step.OursSent(
                    32_000.microseconds,
                    listOf(PacketKind.Handshake, PacketKind.OneRtt),
                    Fate.Unread(47_000.microseconds, listOf(1)),
                ),
                Step.PeerSent(47_200.microseconds, listOf(PacketKind.OneRtt), Fate.Lost),
            ),
            schedule.steps,
        )
        assertEquals(Moment.At(31_500.microseconds), schedule.established)
        assertEquals(ConnectionEnd.IdleTimeout(899_000.microseconds), schedule.end)
        assertEquals("hq-interop", schedule.alpn)
    }

    @Test
    fun theGeneratedSourceIsTheScheduleInTheFixtureDsl() {
        val extracted = assertIs<Extraction.Extracted>(CaptureToSchedule.extract(inputs(wholeTrace(), captures())))
        val source = CaptureToSchedule.generateKotlin(extracted, "aSmallCapture", "a test")
        val steps = source.lines().map { it.trim() }.filter { it.startsWith("sent") || it.startsWith("peerSent") }
        assertEquals(
            listOf(
                "sent(0, 15000, Initial)",
                "peerSent(15500, 30500, Initial, Handshake)",
                "sentLost(31000, Initial, Handshake)",
                "sentUnread(32000, 47000, listOf(1), Handshake, OneRtt)",
                "peerSentLost(47200, OneRtt)",
            ),
            steps,
        )
        assertTrue("internal val aSmallCapture: CaptureSchedule =" in source, source)
        assertTrue("idleTimeout(899000)" in source, source)
    }

    @Test
    fun withoutCapturesOfTheLinkThePeersDatagramsAreTheOnesWeReceived() {
        val extracted =
            assertIs<Extraction.Extracted>(CaptureToSchedule.extract(inputs(wholeTrace(), LinkEvidence.OurRecordsOnly(15.milliseconds))))
        assertEquals(
            listOf(
                Step.PeerSent(15_500.microseconds, listOf(PacketKind.Initial, PacketKind.Handshake), Fate.Delivered(30_500.microseconds)),
            ),
            extracted.schedule.steps.filterIsInstance<Step.PeerSent>(),
        )
        assertTrue(extracted.notes.any { "No capture of the link" in it }, "the generated header says what the schedule cannot know")
    }

    @Test
    fun theQlogPutsAReceivedDatagramWhereQuicheTookItAndNamesWhatWeWereAskedToDo() {
        val qlog =
            file(
                "ours.sqlog",
                listOf(
                    """{"time":0.0,"name":"quic:parameters_set","data":$OUR_PARAMETERS}""",
                    sentPacket(10.0, "initial", number = 0, length = 60, frame = "crypto"),
                    sentPacket(41.0, "initial", number = 1, length = 30, frame = "ack"),
                    sentPacket(41.0, "handshake", number = 2, length = 50, frame = "crypto"),
                    """{"time":41.4,"name":"quic:stream_data_moved","data":$REQUEST_WRITTEN}""",
                    packet(41.5, "received", "initial", number = 0, length = packetSpans(serverHello)[0].until, frame = "crypto"),
                    sentPacket(42.0, "handshake", number = 3, length = 32, frame = "ack"),
                    sentPacket(42.0, "1RTT", number = 4, length = 21, frame = "stream"),
                    """{"time":43.0,"name":"quic:pack""",
                ).joinToString("\n").toByteArray(),
            )
        val extracted =
            assertIs<Extraction.Extracted>(
                CaptureToSchedule.extract(inputs(wholeTrace(), captures(), QlogEvidence.Recorded(qlog))),
            )
        // The trace stamped the peer's datagram before our second; quiche read it after writing that and being handed the request.
        assertEquals(
            listOf("OursSent", "OursSent", "OursDid", "PeerSent", "OursSent", "PeerSent"),
            extracted.schedule.steps.map { it::class.simpleName },
        )
        assertEquals(
            Step.OursDid(31_400.microseconds, OurAction.Write(stream = 0, bytes = 26, end = StreamEnd.Finished)),
            extracted.schedule.steps[2],
        )
        assertEquals(30_000.milliseconds, extracted.schedule.ourTransport.idleTimeout)
        assertEquals(ActiveMigration.Disabled, extracted.schedule.ourTransport.activeMigration)
    }

    @Test
    fun aServersScheduleBeginsWithTheDatagramThatCreatedItsConnectionReadOffItsSideOfTheLink() {
        // A server connection's trace as it was before it recorded what it received: only what it sent.
        val serverTrace = trace(out(40_000, serverHello), out(72_500, done))
        val ahead = 5_000_000L
        val clientSide =
            pcap(
                Captured(ahead + 1_000, CLIENT, SERVER, hello),
                Captured(ahead + 55_000, SERVER, CLIENT, serverHello),
                Captured(ahead + 56_000, CLIENT, SERVER, finished),
                Captured(ahead + 57_000, CLIENT, SERVER, request),
            )
        val serverSide =
            pcap(
                Captured(ahead + 16_000, CLIENT, SERVER, hello),
                Captured(ahead + 40_000, SERVER, CLIENT, serverHello),
                Captured(ahead + 72_000, CLIENT, SERVER, request),
                Captured(ahead + 72_500, SERVER, CLIENT, done),
            )
        val link = LinkEvidence.BothEnds(file("server-side.pcap", serverSide), file("client-side.pcap", clientSide))
        val inputs = CaptureInputs("a-server-capture", "a test", QuicRole.Server, serverTrace, link, QlogEvidence.Absent)
        val extracted = assertIs<Extraction.Extracted>(CaptureToSchedule.extract(inputs))
        assertEquals(
            listOf(
                Step.PeerSent((-15_000).microseconds, listOf(PacketKind.Initial), Fate.Delivered(0.microseconds)),
                Step.OursSent(24_000.microseconds, listOf(PacketKind.Initial, PacketKind.Handshake), Fate.Delivered(39_000.microseconds)),
                Step.PeerSent(40_000.microseconds, listOf(PacketKind.Initial, PacketKind.Handshake), Fate.Lost),
                Step.PeerSent(41_000.microseconds, listOf(PacketKind.Handshake, PacketKind.OneRtt), Fate.Delivered(56_000.microseconds)),
                Step.OursSent(56_500.microseconds, listOf(PacketKind.OneRtt), Fate.Lost),
            ),
            extracted.schedule.steps,
        )
        assertEquals(
            TestCertificate.Ecdsa,
            extracted.schedule.serverCertificate,
            "a flight of one Handshake packet is the small certificate's",
        )
        assertTrue(extracted.notes.any { "records no datagram our endpoint received" in it }, extracted.notes.toString())
    }

    @Test
    fun aQlogThatStopsBeforeTheTraceDoesIsUsedAsFarAsItGoes() {
        val qlog =
            file(
                "open.sqlog",
                listOf(
                    sentPacket(10.0, "initial", number = 0, length = 60, frame = "crypto"),
                    packet(41.5, "received", "initial", number = 0, length = packetSpans(serverHello)[0].until, frame = "crypto"),
                ).joinToString("\n").toByteArray(),
            )
        val extracted =
            assertIs<Extraction.Extracted>(CaptureToSchedule.extract(inputs(wholeTrace(), captures(), QlogEvidence.Recorded(qlog))))
        assertEquals(3, extracted.schedule.steps.count { it is Step.OursSent }, "every datagram of the trace is in the schedule")
        assertTrue(extracted.notes.any { "The qlog stops at our datagram 1 of 3" in it }, extracted.notes.toString())
    }

    @Test
    fun aCaptureThatCannotBeReadIsRefusedByName() {
        val noDatagram = trace("v1 100000 STATE com.ditchoom.socket.quic.QuicConnectionState.Handshaking")
        assertEquals(
            Extraction.Refused(ExtractionRefusal.TraceHoldsNoDatagramOfOurs),
            CaptureToSchedule.extract(inputs(noDatagram, captures())),
        )

        val notACapture = file("garbage.pcap", ByteArray(64) { 7 })
        assertEquals(
            Extraction.Refused(ExtractionRefusal.CaptureUnreadable(notACapture, PcapFault.NotPcapOrPcapng)),
            CaptureToSchedule.extract(inputs(wholeTrace(), LinkEvidence.BothEnds(notACapture, notACapture))),
        )

        val anotherConnection = file("other.pcap", pcap(Captured(1_000, CLIENT, SERVER, done)))
        assertEquals(
            Extraction.Refused(ExtractionRefusal.ConnectionNotInCapture(anotherConnection)),
            CaptureToSchedule.extract(inputs(wholeTrace(), LinkEvidence.BothEnds(anotherConnection, anotherConnection))),
        )

        val tornInTheMiddle = trace(out(1_000, hello), "v1 2000 DGRAM_OUT 1200 - c0", out(3_000, finished))
        assertEquals(
            Extraction.Refused(ExtractionRefusal.TraceLineUnreadable(tornInTheMiddle, 2)),
            CaptureToSchedule.extract(inputs(tornInTheMiddle, captures())),
        )
    }

    @Test
    fun aTraceWhoseLastLineWasCutShortIsReadUpToIt() {
        val torn = trace(out(1_000, hello), received(31_500, serverHello), "v1 32000000 DGRAM_OUT 1200 - c0ff")
        val extracted = assertIs<Extraction.Extracted>(CaptureToSchedule.extract(inputs(torn, captures())))
        assertEquals(1, extracted.schedule.steps.count { it is Step.OursSent })
        assertTrue(extracted.notes.any { "cut short" in it })
    }

    private fun sentPacket(
        time: Double,
        type: String,
        number: Int,
        length: Int,
        frame: String,
    ): String = packet(time, "sent", type, number, length, frame)

    /** A qlog `packet_sent` or `packet_received` line with one frame. */
    private fun packet(
        time: Double,
        direction: String,
        type: String,
        number: Int,
        length: Int,
        frame: String,
    ): String =
        """{"time":$time,"name":"quic:packet_$direction","data":{"header":{"packet_type":"$type","packet_number":$number},""" +
            """"raw":{"length":$length},"frames":[{"frame_type":"$frame"}]}}"""

    private fun file(
        name: String,
        bytes: ByteArray,
    ): File = directory.resolve(name).also { it.writeBytes(bytes) }

    private class Captured(
        val micros: Long,
        val from: UdpEndpoint,
        val to: UdpEndpoint,
        val payload: ByteArray,
    )

    /** A classic little-endian pcap of Ethernet frames, one IPv4 UDP datagram each. */
    private fun pcap(vararg datagrams: Captured): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            ByteBuffer
                .allocate(24)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0xA1B2C3D4.toInt())
                .putShort(2)
                .putShort(4)
                .putInt(0)
                .putInt(0)
                .putInt(65535)
                .putInt(1)
                .array(),
        )
        for (datagram in datagrams) {
            val frame = ByteBuffer.allocate(14 + 20 + 8 + datagram.payload.size)
            frame.position(12)
            frame.putShort(0x0800)
            frame.put(0x45).put(0).putShort((20 + 8 + datagram.payload.size).toShort())
            frame
                .putInt(0)
                .put(64)
                .put(17)
                .putShort(0)
            frame.putInt(datagram.from.address).putInt(datagram.to.address)
            frame.putShort(datagram.from.port.toShort()).putShort(datagram.to.port.toShort())
            frame.putShort((8 + datagram.payload.size).toShort()).putShort(0)
            frame.put(datagram.payload)
            out.write(
                ByteBuffer
                    .allocate(16)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt((datagram.micros / 1_000_000).toInt())
                    .putInt((datagram.micros % 1_000_000).toInt())
                    .putInt(frame.capacity())
                    .putInt(frame.capacity())
                    .array(),
            )
            out.write(frame.array())
        }
        return out.toByteArray()
    }

    private fun datagram(
        packets: ByteArray,
        padding: Int = 0,
    ): ByteArray = packets + ByteArray(padding)

    /** An Initial packet with an empty token. */
    private fun initial(payload: Int): ByteArray = longHeader(0xC0) + byteArrayOf(0, payload.toByte()) + ByteArray(payload) { it.toByte() }

    private fun handshake(payload: Int): ByteArray =
        longHeader(0xE0) + byteArrayOf(payload.toByte()) + ByteArray(payload) { (it + 7).toByte() }

    /** Flags, version 1, and a four-byte connection ID each way. */
    private fun longHeader(first: Int): ByteArray = byteArrayOf(first.toByte(), 0, 0, 0, 1, 4, 1, 2, 3, 4, 4, 5, 6, 7, 8)

    private fun oneRtt(size: Int): ByteArray = byteArrayOf(0x40) + ByteArray(size) { (it + 1).toByte() }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val OUR_PARAMETERS =
            """{"initiator":"local","max_idle_timeout":30000,"active_connection_id_limit":8,"disable_active_migration":true}"""
        const val REQUEST_WRITTEN =
            """{"stream_id":0,"from":"application","to":"transport","additional_info":"fin_set","raw":{"length":26}}"""
        val CLIENT = UdpEndpoint(address = 0xC0000201.toInt(), port = 40000)
        val SERVER = UdpEndpoint(address = 0xC6336402.toInt(), port = 443)
    }
}
