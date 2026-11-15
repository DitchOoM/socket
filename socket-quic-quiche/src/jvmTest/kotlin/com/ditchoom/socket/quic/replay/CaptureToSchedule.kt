package com.ditchoom.socket.quic.replay

import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.testkit.trace.TraceEvent
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What says which datagrams the network delivered. */
internal sealed interface LinkEvidence {
    /** Packet captures taken on each side of the lossy link, as the interop simulator keeps them. */
    class BothEnds(
        val oursSide: File,
        val peerSide: File,
    ) : LinkEvidence

    /**
     * Only our endpoint's own records: a datagram of ours arrived if the peer acknowledged a packet in
     * it, and the peer's datagrams are the ones we received, each [oneWay] after it was sent.
     */
    class OurRecordsOnly(
        val oneWay: Duration,
    ) : LinkEvidence
}

/** Our endpoint's qlog, which says what each packet carried and the order quiche took its inputs in. */
internal sealed interface QlogEvidence {
    class Recorded(
        val file: File,
    ) : QlogEvidence

    data object Absent : QlogEvidence
}

internal class CaptureInputs(
    val name: String,
    /** Where the capture came from, for the generated file's header. */
    val source: String,
    val role: QuicRole,
    val trace: File,
    val link: LinkEvidence,
    val qlog: QlogEvidence,
)

/** Why a capture could not be turned into a schedule. */
internal sealed interface ExtractionRefusal {
    data object TraceHoldsNoDatagramOfOurs : ExtractionRefusal

    data class CaptureUnreadable(
        val file: File,
        val fault: PcapFault,
    ) : ExtractionRefusal

    /** No datagram in [file] has the bytes of the trace's first datagram. */
    data class ConnectionNotInCapture(
        val file: File,
    ) : ExtractionRefusal

    /** Line [line] of the trace, which is not its last, is not a trace line. */
    data class TraceLineUnreadable(
        val file: File,
        val line: Int,
    ) : ExtractionRefusal

    /** Line [line] of the qlog is not JSON. */
    data class QlogUnreadable(
        val file: File,
        val line: Int,
    ) : ExtractionRefusal

    /** The qlog's sent packets are not the packets of the trace's datagrams, from our datagram [ordinal]. */
    data class QlogDoesNotMatchTrace(
        val ordinal: Int,
    ) : ExtractionRefusal
}

internal sealed interface Extraction {
    class Extracted(
        val schedule: CaptureSchedule,
        /** What each step carried, by its position in [CaptureSchedule.steps], where the qlog says. */
        val frames: Map<Int, String>,
        /** What the replay cannot reproduce of this capture, for the generated file's header. */
        val notes: List<String>,
    ) : Extraction

    data class Refused(
        val refusal: ExtractionRefusal,
    ) : Extraction
}

/**
 * Turns a captured connection into the [CaptureSchedule] that replays it (RFC_DETERMINISTIC_SIMULATION.md
 * §4, Tier B) and into the Kotlin source of that schedule.
 *
 * The trace gives our endpoint's datagrams and their times; the link evidence gives each datagram's
 * fate; the qlog gives what our endpoint was asked to do, the transport parameters of both ends, and
 * the order quiche took its inputs in.
 */
internal object CaptureToSchedule {
    private class TraceDatagram(
        val at: Duration,
        val bytes: ByteArray,
    ) {
        val spans = packetSpans(bytes)
    }

    /** One datagram of the capture with its fate decided, before the steps are put in our endpoint's order. */
    private sealed interface Captured {
        val step: Step

        class Ours(
            override val step: Step.OursSent,
        ) : Captured

        /** A peer datagram; [received] is its position among the datagrams our endpoint received. */
        class Peers(
            override val step: Step.PeerSent,
            val received: Received,
        ) : Captured
    }

    private sealed interface Received {
        data class At(
            val ordinal: Int,
        ) : Received

        data object Never : Received
    }

    fun extract(inputs: CaptureInputs): Extraction {
        val notes = mutableListOf<String>()
        val events =
            when (val read = readTrace(inputs.trace)) {
                is TraceRead.Whole -> read.events
                is TraceRead.Torn -> {
                    notes += "The trace's last line was cut short, as a process killed while writing leaves it, and is left out."
                    read.events
                }
                is TraceRead.Unreadable -> return Extraction.Refused(ExtractionRefusal.TraceLineUnreadable(inputs.trace, read.line))
            }
        val outs = events.filterIsInstance<TraceEvent.DgramOut>().map { TraceDatagram(it.at, hex(it.payloadHex)) }
        if (outs.isEmpty()) return Extraction.Refused(ExtractionRefusal.TraceHoldsNoDatagramOfOurs)
        val recorded = events.filterIsInstance<TraceEvent.DgramIn>().map { TraceDatagram(it.at, hex(it.payloadHex)) }
        val link = inputs.link
        val ins =
            if (recorded.isEmpty() && link is LinkEvidence.BothEnds) {
                notes += "The trace records no datagram our endpoint received: they are the ones the capture on our side of the link holds."
                when (val arrived = arrivedOnOurSide(link, outs)) {
                    is Arrived.Datagrams -> arrived.datagrams
                    is Arrived.Refused -> return Extraction.Refused(arrived.refusal)
                }
            } else {
                recorded
            }
        val origin = (outs + ins).minOf { it.at }
        val qlog =
            when (val evidence = inputs.qlog) {
                QlogEvidence.Absent -> Qlog.None
                is QlogEvidence.Recorded ->
                    when (
                        val read =
                            Qlog.read(
                                evidence.file,
                                outs.map {
                                    it.spans.map { span ->
                                        span.kind
                                    }
                                },
                                outs.first().at,
                                // A server reads the datagram that creates its connection before the qlog is open.
                                ins.mapIndexed { index, received -> if (index < unlogged(inputs.role)) emptyList() else received.spans },
                            )
                    ) {
                        is QlogRead.Read -> read.qlog
                        is QlogRead.Refused -> return Extraction.Refused(read.refusal)
                    }
            }
        val (ours, peers) =
            when (link) {
                is LinkEvidence.BothEnds ->
                    when (val fates = fatesFromCaptures(link, outs, ins, qlog)) {
                        is LinkFates.Known -> fates.ours to fates.peers
                        is LinkFates.Refused -> return Extraction.Refused(fates.refusal)
                    }
                is LinkEvidence.OurRecordsOnly -> {
                    notes += "No capture of the link: our datagrams' fates are read from the peer's acknowledgements, " +
                        "and a peer datagram that never arrived is not in the schedule."
                    fatesFromOurRecords(link, outs, ins, qlog)
                }
            }
        when (qlog) {
            Qlog.None ->
                notes +=
                    "No qlog: the schedule holds no stream write and no connection ID issue, " +
                    "and both ends run on default transport parameters."
            is Qlog.Recorded -> {
                notes += qlog.unreproducible
                if (qlog.datagramsCovered < outs.size) {
                    notes += "The qlog stops at our datagram ${qlog.datagramsCovered} of ${outs.size}: from there the schedule holds " +
                        "no action of ours and the steps stand in the trace's order."
                }
            }
        }
        val read =
            when (inputs.link) {
                is LinkEvidence.BothEnds -> unreadByPeer(ours, peers, qlog)
                is LinkEvidence.OurRecordsOnly -> ours
            }
        val ordered = inOurOrder(read, peers, outs, ins, qlog, unlogged(inputs.role))
        val steps = ordered.map { it.rebased(origin) }
        val frames =
            buildMap {
                ordered.forEachIndexed { index, placed -> if (placed.frames.isNotEmpty()) put(index, placed.frames) }
            }
        val states = events.filterIsInstance<TraceEvent.State>()
        val established = states.filter { it.name.endsWith(".Established") }.take(1)
        val closed = states.filter { it.name.endsWith(".Closed") }.take(1)
        val schedule =
            CaptureSchedule(
                name = inputs.name,
                ours = inputs.role,
                alpn = established.fold(UNNEGOTIATED_ALPN) { _, state -> state.detail.orEmpty().ifEmpty { UNNEGOTIATED_ALPN } },
                ourTransport = qlog.local,
                peerTransport = qlog.remote,
                peerPackets = peerPackets(peers),
                serverCertificate = serverCertificate(inputs.role, read),
                steps = steps,
                established = established.fold<TraceEvent.State, Moment>(Moment.Never) { _, state -> Moment.At(state.at - origin) },
                end = closed.fold<TraceEvent.State, ConnectionEnd>(ConnectionEnd.Open) { _, state -> endOf(state, origin) },
                runsFor = events.maxOf { it.at } - origin,
                tolerance = SendTimeTolerance(),
            )
        return Extraction.Extracted(schedule, frames, notes)
    }

    /** How many of the first datagrams [role] receives are read before its qlog opens: a server's creating Initial. */
    private fun unlogged(role: QuicRole): Int =
        when (role) {
            QuicRole.Client -> 0
            QuicRole.Server -> 1
        }

    /** How the trace's `Closed` state says the connection ended. */
    private fun endOf(
        closed: TraceEvent.State,
        origin: Duration,
    ): ConnectionEnd {
        val detail = closed.detail.orEmpty()
        return when {
            detail.startsWith("peer:") -> ConnectionEnd.ClosedByPeer(closed.at - origin)
            detail.startsWith("local: IdleTimeout") -> ConnectionEnd.IdleTimeout(closed.at - origin)
            else -> ConnectionEnd.ClosedByUs(closed.at - origin)
        }
    }

    /** The first of [pool] that [matches], taken out of it: a list of one, or of none. */
    private fun <T> claim(
        pool: MutableList<T>,
        matches: (T) -> Boolean,
    ): List<T> = pool.filter(matches).take(1).onEach { pool.remove(it) }

    /**
     * How the peer packed its handshake flight: [PeerPackets.AsQuiche] when the first datagrams it sent,
     * within [FLIGHT_WITHIN] of each other, held more than one Handshake packet.
     */
    private fun peerPackets(peers: List<Captured.Peers>): PeerPackets {
        val first = peers.map { it.step }.sortedBy { it.at }
        val flight = first.takeWhile { it.at - first.first().at <= FLIGHT_WITHIN }
        return if (flight.sumOf { step -> step.shape.count { it == PacketKind.Handshake } } >
            1
        ) {
            PeerPackets.AsQuiche
        } else {
            PeerPackets.WholeFlights
        }
    }

    /**
     * The test certificate that gives the replay's server the captured server's flight. Only our own
     * server's flight is ours to reproduce: one Handshake packet in its first datagrams is the small
     * certificate, more is the large one. A captured peer's flight is [peerPackets]'s to follow.
     */
    private fun serverCertificate(
        role: QuicRole,
        ours: List<Captured.Ours>,
    ): TestCertificate =
        when (role) {
            QuicRole.Client -> TestCertificate.Rsa
            QuicRole.Server -> {
                val first = ours.map { it.step }
                val flight = first.takeWhile { it.at - first.first().at <= FLIGHT_WITHIN }
                if (flight.sumOf { step -> step.shape.count { it == PacketKind.Handshake } } >
                    1
                ) {
                    TestCertificate.Rsa
                } else {
                    TestCertificate.Ecdsa
                }
            }
        }

    private sealed interface TraceRead {
        class Whole(
            val events: List<TraceEvent>,
        ) : TraceRead

        /** Every line but the last parsed. */
        class Torn(
            val events: List<TraceEvent>,
        ) : TraceRead

        data class Unreadable(
            val line: Int,
        ) : TraceRead
    }

    private fun readTrace(file: File): TraceRead {
        val lines = file.readLines().filter { it.isNotBlank() }
        val events = mutableListOf<TraceEvent>()
        lines.forEachIndexed { index, line ->
            try {
                events += TraceEvent.parse(line)
            } catch (_: IllegalArgumentException) {
                return if (index == lines.lastIndex) TraceRead.Torn(events) else TraceRead.Unreadable(index + 1)
            }
        }
        return TraceRead.Whole(events)
    }

    private sealed interface Arrived {
        class Datagrams(
            val datagrams: List<TraceDatagram>,
        ) : Arrived

        data class Refused(
            val refusal: ExtractionRefusal,
        ) : Arrived
    }

    /**
     * What reached our endpoint, read off the capture on our side of the link and stamped on the trace's
     * clock: for a trace that records none of it, as a server connection's did before its Initial and
     * the datagrams routed to it were recorded there.
     */
    private fun arrivedOnOurSide(
        link: LinkEvidence.BothEnds,
        outs: List<TraceDatagram>,
    ): Arrived {
        val oursSide =
            when (val read = readPcap(link.oursSide)) {
                is PcapRead.Datagrams -> read.datagrams
                is PcapRead.Unreadable -> return Arrived.Refused(ExtractionRefusal.CaptureUnreadable(link.oursSide, read.fault))
            }
        val shared = oursSide.filter { it.payload.contentEquals(outs.first().bytes) }.take(1)
        if (shared.isEmpty()) return Arrived.Refused(ExtractionRefusal.ConnectionNotInCapture(link.oursSide))
        val first = shared.single()
        return Arrived.Datagrams(
            oursSide
                .filter { it.from == first.to && it.to == first.from }
                .map { TraceDatagram(it.at - first.at + outs.first().at, it.payload) },
        )
    }

    private sealed interface LinkFates {
        class Known(
            val ours: List<Captured.Ours>,
            val peers: List<Captured.Peers>,
        ) : LinkFates

        data class Refused(
            val refusal: ExtractionRefusal,
        ) : LinkFates
    }

    private fun fatesFromCaptures(
        link: LinkEvidence.BothEnds,
        outs: List<TraceDatagram>,
        ins: List<TraceDatagram>,
        qlog: Qlog,
    ): LinkFates {
        val oursSide =
            when (val read = readPcap(link.oursSide)) {
                is PcapRead.Datagrams -> read.datagrams
                is PcapRead.Unreadable -> return LinkFates.Refused(ExtractionRefusal.CaptureUnreadable(link.oursSide, read.fault))
            }
        val peerSide =
            when (val read = readPcap(link.peerSide)) {
                is PcapRead.Datagrams -> read.datagrams
                is PcapRead.Unreadable -> return LinkFates.Refused(ExtractionRefusal.CaptureUnreadable(link.peerSide, read.fault))
            }
        val shared = oursSide.filter { it.payload.contentEquals(outs.first().bytes) }.take(1)
        if (shared.isEmpty()) return LinkFates.Refused(ExtractionRefusal.ConnectionNotInCapture(link.oursSide))
        val first = shared.single()
        // The captures share a clock; the trace has its own. One datagram both recorded sets them against each other.
        val onTraceClock = { captured: Duration -> captured - first.at + outs.first().at }
        val reachedPeer = peerSide.filter { it.from == first.from && it.to == first.to }.toMutableList()
        val peerSent = peerSide.filter { it.from == first.to && it.to == first.from }

        // Every datagram that arrived as sent is claimed before any is taken for a damaged copy of another.
        val oursIntact = outs.map { out -> claim(reachedPeer) { it.payload.contentEquals(out.bytes) } }
        val ours =
            outs.mapIndexed { index, out ->
                val damaged =
                    if (oursIntact[index].isEmpty()) {
                        claim(reachedPeer) {
                            it.payload.size == out.bytes.size &&
                                onTraceClock(it.at) >= out.at &&
                                onTraceClock(it.at) - out.at <= DAMAGED_WITHIN
                        }
                    } else {
                        emptyList()
                    }
                val fate =
                    (
                        oursIntact[index].map<UdpDatagram, Fate> { Fate.Delivered(onTraceClock(it.at)) } +
                            damaged.map { Fate.Unread(onTraceClock(it.at), damagedPackets(out, it.payload)) } +
                            Fate.Lost
                    ).first()
                Captured.Ours(Step.OursSent(out.at, out.spans.map { it.kind }, fate, qlog.carries(index)))
            }

        val unclaimed = ins.indices.toMutableList()
        val peersIntact = peerSent.map { sentDatagram -> claim(unclaimed) { ins[it].bytes.contentEquals(sentDatagram.payload) } }
        val peers =
            peerSent.mapIndexed { index, sentDatagram ->
                val sentAt = onTraceClock(sentDatagram.at)
                val sentAs = TraceDatagram(sentAt, sentDatagram.payload)
                val damaged =
                    if (peersIntact[index].isEmpty()) {
                        claim(unclaimed) {
                            ins[it].bytes.size == sentDatagram.payload.size &&
                                ins[it].at >= sentAt &&
                                ins[it].at - sentAt <= DAMAGED_WITHIN
                        }
                    } else {
                        emptyList()
                    }
                val shape = sentAs.spans.map { it.kind }
                (
                    peersIntact[index].map { Captured.Peers(Step.PeerSent(sentAt, shape, Fate.Delivered(ins[it].at)), Received.At(it)) } +
                        damaged.map {
                            Captured.Peers(
                                Step.PeerSent(sentAt, shape, Fate.Unread(ins[it].at, damagedPackets(sentAs, ins[it].bytes))),
                                Received.At(it),
                            )
                        } +
                        Captured.Peers(Step.PeerSent(sentAt, shape, Fate.Lost), Received.Never)
                ).first()
            }
        return LinkFates.Known(ours, peers)
    }

    /** The coalesced packets of [sent] that hold a byte [arrived] differs in. */
    private fun damagedPackets(
        sent: TraceDatagram,
        arrived: ByteArray,
    ): List<Int> {
        val differing = sent.bytes.indices.filter { sent.bytes[it] != arrived[it] }
        return sent.spans.indices.filter { index -> differing.any { it >= sent.spans[index].from && it < sent.spans[index].until } }
    }

    private fun fatesFromOurRecords(
        link: LinkEvidence.OurRecordsOnly,
        outs: List<TraceDatagram>,
        ins: List<TraceDatagram>,
        qlog: Qlog,
    ): Pair<List<Captured.Ours>, List<Captured.Peers>> {
        val ours =
            outs.mapIndexed { index, out ->
                val fate =
                    when (qlog) {
                        Qlog.None -> Fate.Delivered(out.at + link.oneWay)
                        is Qlog.Recorded -> if (qlog.peerSaw(index)) Fate.Delivered(out.at + link.oneWay) else Fate.Lost
                    }
                Captured.Ours(Step.OursSent(out.at, out.spans.map { it.kind }, fate, qlog.carries(index)))
            }
        val peers =
            ins.mapIndexed { index, received ->
                Captured.Peers(
                    Step.PeerSent(received.at - link.oneWay, received.spans.map { it.kind }, Fate.Delivered(received.at)),
                    Received.At(index),
                )
            }
        return ours to peers
    }

    /**
     * [ours] with each delivered datagram's unread packets marked. A packet that asks for an
     * acknowledgement gets one within the peer's `max_ack_delay` (RFC 9000 §13.2.1), so the peer did not
     * read a packet that was never acknowledged and that the peer sent nothing at all for
     * [ACK_DELAYS_WAITED] such delays after. [peers] must hold every datagram the peer sent, the lost
     * ones too.
     */
    private fun unreadByPeer(
        ours: List<Captured.Ours>,
        peers: List<Captured.Peers>,
        qlog: Qlog,
    ): List<Captured.Ours> =
        when (qlog) {
            Qlog.None -> ours
            is Qlog.Recorded ->
                ours.mapIndexed { index, captured ->
                    when (val fate = captured.step.fate) {
                        Fate.Lost, is Fate.Unread -> captured
                        is Fate.Delivered -> {
                            val silent =
                                peers.none {
                                    it.step.at >= fate.at && it.step.at <= fate.at + qlog.peerAckDelay * ACK_DELAYS_WAITED
                                }
                            val unread = if (silent) qlog.unacknowledged(index) else emptyList()
                            if (unread.isEmpty()) captured else Captured.Ours(captured.step.copy(fate = Fate.Unread(fate.at, unread)))
                        }
                    }
                }
        }

    /** A step where our endpoint took it, with what the qlog says it carried. */
    private class Placed(
        val step: Step,
        val frames: String,
    ) {
        fun rebased(origin: Duration): Step =
            when (step) {
                is Step.OursSent -> step.copy(at = step.at - origin, fate = step.fate.rebased(origin))
                is Step.PeerSent -> step.copy(at = step.at - origin, fate = step.fate.rebased(origin))
                is Step.OursDid -> step.copy(at = step.at - origin)
            }

        private fun Fate.rebased(origin: Duration): Fate =
            when (this) {
                Fate.Lost -> this
                is Fate.Delivered -> Fate.Delivered(at - origin)
                is Fate.Unread -> Fate.Unread(at - origin, packets)
            }
    }

    /**
     * The capture's steps in the order our endpoint took them. With a qlog that is quiche's own order of
     * packets received, packets sent and stream writes; a datagram the qlog does not show received was
     * discarded unread, and stands where the trace's clock puts it, as every datagram does without a qlog.
     */
    private fun inOurOrder(
        ours: List<Captured.Ours>,
        peers: List<Captured.Peers>,
        outs: List<TraceDatagram>,
        ins: List<TraceDatagram>,
        qlog: Qlog,
        unlogged: Int,
    ): List<Placed> {
        val placed = mutableListOf<Placed>()
        val placedOuts = mutableSetOf<Int>()
        val placedIns = mutableSetOf<Int>()

        fun placeOut(index: Int) {
            if (placedOuts.add(index)) placed += Placed(ours[index].step, qlog.sentFrames(index))
        }

        fun placeIn(index: Int) {
            peers.filter { it.received == Received.At(index) }.take(1).forEach { peer ->
                if (placedIns.add(index)) {
                    val step = peer.step.copy(wrote = qlog.peerWrote(index), carries = qlog.receivedCarries(index))
                    placed += Placed(step, qlog.receivedFrames(index))
                }
            }
        }

        /** Datagrams our endpoint received by [before] that quiche read no packet of. */
        fun placeUnreadBefore(
            before: Duration,
            read: Set<Int>,
        ) {
            ins.indices.filter { it !in read && ins[it].at <= before }.forEach { placeIn(it) }
        }

        ins.indices.take(unlogged).forEach { placeIn(it) }
        when (qlog) {
            Qlog.None -> {
                val byTraceClock =
                    (outs.indices.map { outs[it].at to { placeOut(it) } } + ins.indices.map { ins[it].at to { placeIn(it) } })
                        .sortedBy { it.first }
                byTraceClock.forEach { it.second() }
            }
            is Qlog.Recorded ->
                for (entry in qlog.order()) {
                    when (entry) {
                        is QlogEntry.Sent -> {
                            if (entry.datagram !in placedOuts) placeUnreadBefore(outs[entry.datagram].at, qlog.readDatagrams)
                            placeOut(entry.datagram)
                        }
                        is QlogEntry.Received -> placeIn(entry.datagram)
                        is QlogEntry.Did -> placed += Placed(Step.OursDid(entry.at, entry.action), "")
                    }
                }
        }
        // What the qlog does not reach stands where the trace's clock puts it.
        (outs.indices.map { outs[it].at to { placeOut(it) } } + ins.indices.map { ins[it].at to { placeIn(it) } })
            .sortedBy { it.first }
            .forEach { it.second() }
        // A datagram that never arrived has no place in our endpoint's order; it reads best where it was sent.
        for (lost in peers.filter { it.received == Received.Never }) {
            val after = placed.indexOfLast { it.step.at <= lost.step.at }
            placed.add(after + 1, Placed(lost.step, ""))
        }
        return placed
    }

    /** The Kotlin source of [extracted]'s schedule, as `internal val [valName]` in [packageName]. */
    fun generateKotlin(
        extracted: Extraction.Extracted,
        valName: String,
        source: String,
        packageName: String = "com.ditchoom.socket.quic.replay.fixtures",
    ): String {
        val schedule = extracted.schedule
        val kinds =
            schedule.steps
                .flatMap {
                    when (it) {
                        is Step.OursSent -> it.shape
                        is Step.PeerSent -> it.shape
                        is Step.OursDid -> emptyList()
                    }
                }.toSortedSet()
        val writes =
            schedule.steps.any {
                when (it) {
                    is Step.OursDid -> it.action is OurAction.Write
                    is Step.PeerSent -> it.wrote.isNotEmpty()
                    is Step.OursSent -> false
                }
            }
        val imports =
            buildList {
                add("com.ditchoom.socket.quic.QuicRole")
                add("com.ditchoom.socket.quic.replay.ActiveMigration")
                add("com.ditchoom.socket.quic.replay.CaptureSchedule")
                add("com.ditchoom.socket.quic.replay.PeerPackets")
                add("com.ditchoom.socket.quic.replay.TestCertificate")
                kinds.forEach { add("com.ditchoom.socket.quic.replay.PacketKind.${it.name}") }
                if (writes) add("com.ditchoom.socket.quic.replay.StreamEnd")
                add("com.ditchoom.socket.quic.replay.TransportSetup")
                add("com.ditchoom.socket.quic.replay.captureSchedule")
                add("kotlin.time.Duration.Companion.milliseconds")
            }.sorted()
        return buildString {
            appendLine("// GENERATED by CaptureToSchedule — regenerate, do not edit.")
            appendLine("// Source: $source")
            extracted.notes.forEach { appendLine("// Note: $it") }
            appendLine("// Times are microseconds from the capture's first datagram; steps are in the order our endpoint took them.")
            appendLine("@file:Suppress(\"ktlint\")")
            appendLine()
            appendLine("package $packageName")
            appendLine()
            imports.forEach { appendLine("import $it") }
            appendLine()
            appendLine("internal val $valName: CaptureSchedule =")
            appendLine("    captureSchedule(${literal(schedule.name)}, QuicRole.${roleName(schedule.ours)}, ${literal(schedule.alpn)}) {")
            appendLine("        ourTransport(${render(schedule.ourTransport)})")
            appendLine("        peerTransport(${render(schedule.peerTransport)})")
            appendLine("        peerPackets(PeerPackets.${schedule.peerPackets.name})")
            appendLine("        serverCertificate(TestCertificate.${schedule.serverCertificate.name})")
            schedule.steps.forEachIndexed { index, step ->
                peerWrites(
                    step,
                ).forEach { appendLine("        peerWrote(stream = ${it.stream}, bytes = ${it.bytes}, end = StreamEnd.${it.end.name})") }
                val carried = extracted.frames.getOrElse(index) { "" }
                appendLine("        ${render(step)}${if (carried.isEmpty()) "" else " // $carried"}")
            }
            when (val established = schedule.established) {
                Moment.Never -> Unit
                is Moment.At -> appendLine("        established(${micros(established.at)})")
            }
            when (val end = schedule.end) {
                ConnectionEnd.Open -> Unit
                is ConnectionEnd.IdleTimeout -> appendLine("        idleTimeout(${micros(end.at)})")
                is ConnectionEnd.ClosedByUs -> appendLine("        closedByUs(${micros(end.at)})")
                is ConnectionEnd.ClosedByPeer -> appendLine("        closedByPeer(${micros(end.at)})")
            }
            appendLine("        runsFor(${micros(schedule.runsFor)})")
            appendLine("    }")
        }
    }

    /**
     * A test for [valName] to paste into `CaptureReplayTestSuite`: red while the captured failure
     * reproduces, green once our endpoint gets its streams through on the same network.
     */
    fun generateTestSkeleton(
        extracted: Extraction.Extracted,
        valName: String,
    ): String {
        val schedule = extracted.schedule
        val lastLoss =
            schedule.steps.fold(Duration.ZERO) { latest, step ->
                when (step) {
                    is Step.OursSent -> if (step.fate is Fate.Delivered) latest else maxOf(latest, step.at)
                    is Step.PeerSent -> if (step.fate is Fate.Delivered) latest else maxOf(latest, step.at)
                    is Step.OursDid -> latest
                }
            }
        val streams =
            schedule.steps
                .filterIsInstance<Step.OursDid>()
                .map {
                    it.action
                }.filterIsInstance<OurAction.Write>()
                .map { it.stream }
                .distinct()
        return buildString {
            appendLine("    @Test")
            appendLine("    fun ${valName}DeliversWhatItWrote() =")
            appendLine("        runTest {")
            appendLine("            wrapTestBody {")
            appendLine("                val beyond = Beyond.HealthyPath(oneWay = 15.milliseconds, runFor = 10.seconds)")
            appendLine("                val report = replayCapture(simEnv($valName.serverCertificate), $valName, beyond)")
            appendLine("                // The scenario is every loss up to here. Once a fix makes the replay leave the capture sooner,")
            appendLine("                // lower this to just before that point, having checked the fix is what moved it.")
            appendLine("                assertTrue(report.followedCaptureUntil(${micros(lastLoss)}.microseconds), report.describe())")
            appendLine("                assertIs<Moment.At>(report.established, report.describe())")
            appendLine("                // Give each stream the time by which the peer must have read it.")
            streams.forEach {
                appendLine("                assertTrue(report.peerReadToTheEndBy(stream = $it, at = report.ranFor), report.describe())")
            }
            appendLine("            }")
            appendLine("        }")
        }
    }

    private fun render(step: Step): String {
        val at = micros(step.at)
        return when (step) {
            is Step.OursSent -> {
                val shape = shape(step.shape)
                when (val fate = step.fate) {
                    Fate.Lost -> "${sent(step.carries)}Lost($at, $shape)"
                    is Fate.Delivered -> "${sent(step.carries)}($at, ${micros(fate.at)}, $shape)"
                    is Fate.Unread -> "sentUnread($at, ${micros(fate.at)}, listOf(${fate.packets.joinToString()}), $shape)"
                }
            }
            is Step.PeerSent -> {
                val shape = shape(step.shape)
                when (val fate = step.fate) {
                    Fate.Lost -> "peerSentLost($at, $shape)"
                    is Fate.Delivered -> "peerSent${if (step.carries == Carries.AcksOnly) "Acks" else ""}($at, ${micros(fate.at)}, $shape)"
                    is Fate.Unread -> "peerSentUnread($at, ${micros(fate.at)}, listOf(${fate.packets.joinToString()}), $shape)"
                }
            }
            is Step.OursDid ->
                when (val action = step.action) {
                    is OurAction.IssueConnectionIds -> "issuedConnectionIds($at, count = ${action.count})"
                    OurAction.Close -> "closed($at)"
                    is OurAction.Write ->
                        "wrote($at, stream = ${action.stream}, bytes = ${action.bytes}, end = StreamEnd.${action.end.name})"
                }
        }
    }

    private fun peerWrites(step: Step): List<PeerWrite> =
        when (step) {
            is Step.PeerSent -> step.wrote
            is Step.OursSent, is Step.OursDid -> emptyList()
        }

    private fun render(setup: TransportSetup): String =
        "TransportSetup(idleTimeout = ${setup.idleTimeout.inWholeMilliseconds}.milliseconds, " +
            "activeConnectionIdLimit = ${setup.activeConnectionIdLimit}, " +
            "activeMigration = ActiveMigration.${setup.activeMigration.name}, " +
            "maxData = ${setup.maxData}, maxStreamDataBidiLocal = ${setup.maxStreamDataBidiLocal}, " +
            "maxStreamDataBidiRemote = ${setup.maxStreamDataBidiRemote}, maxStreamDataUni = ${setup.maxStreamDataUni}, " +
            "maxStreamsBidi = ${setup.maxStreamsBidi}, maxStreamsUni = ${setup.maxStreamsUni})"

    private fun sent(carries: Carries): String =
        when (carries) {
            Carries.Data -> "sent"
            Carries.AcksOnly -> "sentAcks"
        }

    private fun shape(shape: List<PacketKind>): String = shape.joinToString { it.name }

    private fun micros(at: Duration): String = at.inWholeMicroseconds.toString()

    private fun roleName(role: QuicRole): String =
        when (role) {
            QuicRole.Client -> "Client"
            QuicRole.Server -> "Server"
        }

    private fun literal(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Datagrams the peer sent this close together are one flight. */
    private val FLIGHT_WITHIN = 1.milliseconds

    /** How many of the peer's acknowledgement delays of silence say a packet went unread. */
    private const val ACK_DELAYS_WAITED = 3

    /** What both ends speak in a replay of a connection that never negotiated a protocol. */
    private const val UNNEGOTIATED_ALPN = "capture"

    /** How long after it was sent a datagram of the same length is still taken for a damaged copy of it. */
    private val DAMAGED_WITHIN = 1.seconds
}

/** One thing quiche did, in the order it did them. */
internal sealed interface QlogEntry {
    /** The first packet of our datagram [datagram] was sent. */
    data class Sent(
        val datagram: Int,
    ) : QlogEntry

    /** The first readable packet of received datagram [datagram] was taken in. */
    data class Received(
        val datagram: Int,
    ) : QlogEntry

    data class Did(
        val at: Duration,
        val action: OurAction,
    ) : QlogEntry
}

/** Which received datagram held a packet the qlog shows received. */
internal sealed interface Placement {
    data class In(
        val datagram: Int,
    ) : Placement

    /** No received datagram holds a packet of its type and length. */
    data object Nowhere : Placement
}

internal sealed interface QlogRead {
    class Read(
        val qlog: Qlog.Recorded,
    ) : QlogRead

    data class Refused(
        val refusal: ExtractionRefusal,
    ) : QlogRead
}

internal sealed interface Qlog {
    val local: TransportSetup
    val remote: TransportSetup

    fun sentFrames(datagram: Int): String

    /** What our datagram [datagram] was for. */
    fun carries(datagram: Int): Carries

    fun receivedFrames(datagram: Int): String

    /** What received datagram [datagram] was for. */
    fun receivedCarries(datagram: Int): Carries

    /** What the peer's application wrote that received datagram [datagram] was the first to carry. */
    fun peerWrote(datagram: Int): List<PeerWrite>

    data object None : Qlog {
        override val local = TransportSetup()
        override val remote = TransportSetup()

        override fun sentFrames(datagram: Int): String = ""

        override fun carries(datagram: Int): Carries = Carries.Data

        override fun receivedFrames(datagram: Int): String = ""

        override fun peerWrote(datagram: Int): List<PeerWrite> = emptyList()

        override fun receivedCarries(datagram: Int): Carries = Carries.Data
    }

    class Recorded(
        private val events: List<Event>,
        /** For each sent packet event, in order, the datagram of ours it left in. */
        private val datagramOfSent: List<Int>,
        /** Offset that brings a qlog time onto the trace's clock. */
        private val toTraceClock: Duration,
        /** The packets of each datagram our endpoint received. */
        receivedDatagrams: List<List<PacketSpan>>,
    ) : Qlog {
        class Event(
            val at: Duration,
            val name: String,
            val data: Json,
        )

        private val sent = events.filter { it.name == PACKET_SENT }
        private val received = events.filter { it.name == PACKET_RECEIVED }

        override val local = setup("local")
        override val remote = setup("remote")

        /** What the bound quiche configuration cannot be told of the peer's transport parameters. */
        val unreproducible: List<String> =
            events
                .filter { it.name == PARAMETERS_SET && it.data["initiator"].text() == "remote" }
                .flatMap { set ->
                    buildList {
                        val maxAckDelay = set.data["max_ack_delay"].number(QUICHE_MAX_ACK_DELAY_MS)
                        val exponent = set.data["ack_delay_exponent"].number(QUICHE_ACK_DELAY_EXPONENT)
                        if (maxAckDelay != QUICHE_MAX_ACK_DELAY_MS) {
                            add("The peer's max_ack_delay was ${maxAckDelay.toInt()} ms; the modelled peer advertises quiche's.")
                        }
                        if (exponent != QUICHE_ACK_DELAY_EXPONENT) {
                            add("The peer's ack_delay_exponent was ${exponent.toInt()}; the modelled peer advertises quiche's.")
                        }
                    }
                }

        /** The transport parameters [initiator] set; [Json.Null] when the qlog holds none of its. */
        private fun parameters(initiator: String): Json =
            events
                .filter { it.name == PARAMETERS_SET && it.data["initiator"].text() == initiator }
                .take(1)
                .fold<Event, Json>(Json.Null) { _, event -> event.data }

        private fun setup(initiator: String): TransportSetup {
            val defaults = TransportSetup()
            val set = parameters(initiator)
            return TransportSetup(
                idleTimeout = set["max_idle_timeout"].number(defaults.idleTimeout.inWholeMilliseconds.toDouble()).toLong().milliseconds,
                activeConnectionIdLimit = set["active_connection_id_limit"].number(defaults.activeConnectionIdLimit.toDouble()).toLong(),
                activeMigration = if (set["disable_active_migration"].flag()) ActiveMigration.Disabled else ActiveMigration.Allowed,
                maxData = set["initial_max_data"].number(defaults.maxData.toDouble()).toLong(),
                maxStreamDataBidiLocal =
                    set["initial_max_stream_data_bidi_local"]
                        .number(
                            defaults.maxStreamDataBidiLocal.toDouble(),
                        ).toLong(),
                maxStreamDataBidiRemote =
                    set["initial_max_stream_data_bidi_remote"]
                        .number(
                            defaults.maxStreamDataBidiRemote.toDouble(),
                        ).toLong(),
                maxStreamDataUni = set["initial_max_stream_data_uni"].number(defaults.maxStreamDataUni.toDouble()).toLong(),
                maxStreamsBidi = set["initial_max_streams_bidi"].number(defaults.maxStreamsBidi.toDouble()).toLong(),
                maxStreamsUni = set["initial_max_streams_uni"].number(defaults.maxStreamsUni.toDouble()).toLong(),
            )
        }

        private fun frames(event: Event): List<String> =
            event.data["frames"]
                .elements
                .map { it["frame_type"].text() }
                .filter { it != "padding" }

        override fun sentFrames(datagram: Int): String =
            sent.indices.filter { datagramOfSent[it] == datagram }.joinToString(" | ") { frames(sent[it]).joinToString(",") }

        override fun receivedFrames(datagram: Int): String =
            received.indices
                .filter {
                    datagramOfReceived[it] ==
                        Placement.In(
                            datagram,
                        )
                }.joinToString(" | ") { frames(received[it]).joinToString(",") }

        /**
         * For each received packet event, the received datagram that held it: the earliest datagram with
         * an unclaimed packet of that type and length.
         */
        private val datagramOfReceived: List<Placement> =
            run {
                val unclaimed =
                    receivedDatagrams
                        .flatMapIndexed { datagram, spans -> spans.map { Triple(datagram, it.kind, it.until - it.from) } }
                        .toMutableList()
                received.map { event ->
                    val kind = kindOf(event.data["header"]["packet_type"].text())
                    val length = event.data["raw"]["length"]
                    unclaimed
                        .filter {
                            it.second == kind &&
                                (kind == PacketKind.OneRtt || (length is Json.Num && it.third == length.value.toInt()))
                        }.take(1)
                        .onEach { unclaimed.remove(it) }
                        .fold<Triple<Int, PacketKind, Int>, Placement>(Placement.Nowhere) { _, hit -> Placement.In(hit.first) }
                }
            }

        /** For each received packet event, the stream bytes it was the first to carry. */
        private val writesOfReceived: List<List<PeerWrite>> =
            run {
                val carried = mutableMapOf<Long, Long>()
                val finished = mutableSetOf<Long>()
                received.map { event ->
                    event.data["frames"]
                        .elements
                        .filter { it["frame_type"].text() == "stream" }
                        .flatMap { frame ->
                            val stream = frame["stream_id"].number().toLong()
                            val reaches = frame["offset"].number().toLong() + frame["raw"]["payload_length"].number().toLong()
                            val fresh = reaches - carried.getOrElse(stream) { 0L }
                            val ends = frame["fin"].flag() && finished.add(stream)
                            if (fresh > 0) carried[stream] = reaches
                            if (fresh > 0 || ends) {
                                listOf(PeerWrite(stream, maxOf(fresh, 0L).toInt(), if (ends) StreamEnd.Finished else StreamEnd.Open))
                            } else {
                                emptyList()
                            }
                        }
                }
            }

        override fun receivedCarries(datagram: Int): Carries {
            val packets = received.indices.filter { datagramOfReceived[it] == Placement.In(datagram) }.map { received[it] }
            return if (packets.isNotEmpty() && packets.none { asksForAcknowledgement(it) }) Carries.AcksOnly else Carries.Data
        }

        override fun peerWrote(datagram: Int): List<PeerWrite> =
            received.indices.filter { datagramOfReceived[it] == Placement.In(datagram) }.flatMap { writesOfReceived[it] }

        /** The received datagrams quiche read a packet of. */
        val readDatagrams: Set<Int> = datagramOfReceived.filterIsInstance<Placement.In>().map { it.datagram }.toSet()

        /** The peer's `max_ack_delay`. */
        val peerAckDelay: Duration = parameters("remote")["max_ack_delay"].number(QUICHE_MAX_ACK_DELAY_MS).toLong().milliseconds

        /** Every packet the peer acknowledged, as its qlog packet type and number. */
        private val acknowledged: Set<Pair<String, Long>> =
            received
                .flatMap { event ->
                    val kind = event.data["header"]["packet_type"].text()
                    event.data["frames"]
                        .elements
                        .filter { it["frame_type"].text() == "ack" }
                        .flatMap { ack ->
                            ack["acked_ranges"].elements.flatMap { range ->
                                val bounds = range.elements.map { it.number().toLong() }
                                if (bounds.isEmpty()) emptyList() else (bounds.first()..bounds.last()).map { kind to it }
                            }
                        }
                }.toSet()

        private fun packetsOf(datagram: Int): List<Event> = sent.indices.filter { datagramOfSent[it] == datagram }.map { sent[it] }

        private fun asksForAcknowledgement(packet: Event): Boolean = frames(packet).any { it != "ack" }

        private fun acknowledged(packet: Event): Boolean =
            when (val number = packet.data["header"]["packet_number"]) {
                is Json.Num -> (packet.data["header"]["packet_type"].text() to number.value.toLong()) in acknowledged
                is Json.Obj, is Json.Arr, is Json.Str, is Json.Bool, Json.Null, is Json.Malformed -> false
            }

        /** The positions in our datagram [datagram] of the packets that asked for an acknowledgement and never got one. */
        fun unacknowledged(datagram: Int): List<Int> =
            packetsOf(datagram).indices.filter { asksForAcknowledgement(packetsOf(datagram)[it]) && !acknowledged(packetsOf(datagram)[it]) }

        /** How many of our datagrams, from the first, the qlog holds the packets of. */
        val datagramsCovered: Int = datagramOfSent.toSet().size

        override fun carries(datagram: Int): Carries {
            val packets = packetsOf(datagram)
            return if (packets.isNotEmpty() && packets.none { asksForAcknowledgement(it) }) Carries.AcksOnly else Carries.Data
        }

        /** Whether the peer acknowledged a packet of our datagram [datagram], or the datagram asked for no acknowledgement. */
        fun peerSaw(datagram: Int): Boolean =
            packetsOf(datagram).none { asksForAcknowledgement(it) } || packetsOf(datagram).any { acknowledged(it) }

        /** quiche's order of sends, receipts and the actions asked of it. */
        fun order(): List<QlogEntry> {
            val issued = mutableSetOf<Long>()
            var sentIndex = 0
            var receivedIndex = 0
            var closing = 0
            return buildList {
                for (event in events) {
                    when (event.name) {
                        PACKET_SENT -> {
                            val fresh =
                                event.data["frames"]
                                    .elements
                                    .filter { it["frame_type"].text() == "new_connection_id" }
                                    .map { it["sequence_number"].number().toLong() }
                                    .filter { issued.add(it) }
                            if (fresh.isNotEmpty()) add(QlogEntry.Did(event.at + toTraceClock, OurAction.IssueConnectionIds(fresh.size)))
                            val closes = event.data["frames"].elements.any { it["frame_type"].text() == "connection_close" }
                            if (closes && closing++ == 0) add(QlogEntry.Did(event.at + toTraceClock, OurAction.Close))
                            if (sentIndex < datagramOfSent.size) add(QlogEntry.Sent(datagramOfSent[sentIndex]))
                            sentIndex++
                        }
                        PACKET_RECEIVED -> {
                            when (val placement = datagramOfReceived[receivedIndex++]) {
                                Placement.Nowhere -> Unit
                                is Placement.In -> add(QlogEntry.Received(placement.datagram))
                            }
                        }
                        STREAM_DATA_MOVED -> {
                            val bytes = event.data["raw"]["length"].number().toInt()
                            val finished = event.data["additional_info"].text() == "fin_set"
                            if (event.data["from"].text() == "application" && (bytes > 0 || finished)) {
                                val action =
                                    OurAction.Write(
                                        stream = event.data["stream_id"].number().toLong(),
                                        bytes = bytes,
                                        end = if (finished) StreamEnd.Finished else StreamEnd.Open,
                                    )
                                add(QlogEntry.Did(event.at + toTraceClock, action))
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val PACKET_SENT = "quic:packet_sent"
        private const val PACKET_RECEIVED = "quic:packet_received"
        private const val PARAMETERS_SET = "quic:parameters_set"
        private const val STREAM_DATA_MOVED = "quic:stream_data_moved"
        private const val QUICHE_MAX_ACK_DELAY_MS = 25.0
        private const val QUICHE_ACK_DELAY_EXPONENT = 3.0

        private fun kindOf(type: String): PacketKind =
            when (type) {
                "initial" -> PacketKind.Initial
                "handshake" -> PacketKind.Handshake
                "0RTT" -> PacketKind.ZeroRtt
                "retry" -> PacketKind.Retry
                else -> PacketKind.OneRtt
            }

        /**
         * Read a quiche qlog (JSON-SEQ) whose sent packets are those of [sentShapes], our datagrams in
         * order, the first of which the trace stamps [firstSentAt].
         */
        fun read(
            file: File,
            sentShapes: List<List<PacketKind>>,
            firstSentAt: Duration,
            receivedDatagrams: List<List<PacketSpan>>,
        ): QlogRead {
            val events = mutableListOf<Recorded.Event>()
            val lines = file.readLines()
            val lastLine = lines.indexOfLast { it.isNotBlank() }
            lines.forEachIndexed { index, raw ->
                val line = raw.trim().trimStart('\u001E')
                if (line.isEmpty()) return@forEachIndexed
                when (val json = Json.parse(line)) {
                    // A qlog cut at its size budget, or by its process ending, stops mid-line.
                    is Json.Malformed -> if (index != lastLine) return QlogRead.Refused(ExtractionRefusal.QlogUnreadable(file, index + 1))
                    else -> {
                        val name = json["name"].text()
                        if (name.isNotEmpty()) {
                            events +=
                                Recorded.Event((json["time"].number() * 1000).toLong().microseconds, name, json["data"])
                        }
                    }
                }
            }
            val sentKinds = events.filter { it.name == PACKET_SENT }.map { kindOf(it.data["header"]["packet_type"].text()) }
            val datagramOfSent = mutableListOf<Int>()
            // A qlog written out only as its connection closes stops short of a trace taken while it is open.
            for ((datagram, shape) in sentShapes.withIndex()) {
                val from = datagramOfSent.size
                if (from + shape.size > sentKinds.size) break
                if (sentKinds.subList(from, from + shape.size) != shape) {
                    return QlogRead.Refused(ExtractionRefusal.QlogDoesNotMatchTrace(datagram))
                }
                repeat(shape.size) { datagramOfSent += datagram }
            }
            val firstSent = events.first { it.name == PACKET_SENT }
            return QlogRead.Read(Recorded(events, datagramOfSent, firstSentAt - firstSent.at, receivedDatagrams))
        }
    }
}
