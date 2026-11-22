package com.ditchoom.socket.quic.replay

import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.testkit.trace.TraceEvent
import kotlin.time.Duration

/**
 * Turns the two recordings of one connection, ours and the peer's, each in the v1 trace grammar (what the core's flight
 * recorder writes), into the [CaptureSchedule] [replayCapture] runs. No pcap is needed: both ends recorded every
 * datagram's bytes, so each datagram one end sent is found among those the other end received by its bytes.
 *
 * Each datagram becomes its ordinal in its direction, the packet types it coalesced, and its fate: delivered at the
 * time the other end recorded it (which carries any delay), or lost when the other end never did. The two recordings
 * keep clocks of their own; they are aligned on the first datagram both hold, as sent at the time it arrived (a
 * loopback or in-memory link). A datagram is found by its bytes wherever it travelled: the four-tuple each record carries is
 * not in the schedule, so a connection whose client changed address converts like any other, and its replay follows
 * the datagrams, not the path they took. Our recording holds every time its host ran quiche's timeout, so our replayed
 * timers fire when that host next ran one, and never past the last ([OurTimers.AsRecorded]).
 */
internal object RecordingToSchedule {
    /** What a conversion made. */
    sealed interface Converted {
        class Schedule(
            val schedule: CaptureSchedule,
        ) : Converted

        /** No datagram of ours reached the peer, so the clocks cannot be aligned. */
        data object NothingShared : Converted
    }

    private class Datagram(
        val ordinal: Int,
        val at: Duration,
        val hex: String,
    )

    fun convert(
        name: String,
        ours: QuicRole,
        alpn: String,
        ourRecording: List<TraceEvent>,
        peerRecording: List<TraceEvent>,
        ourTransport: TransportSetup = TransportSetup(),
        peerTransport: TransportSetup = TransportSetup(),
        actions: List<RecordedAction> = emptyList(),
        peerApp: PeerApp = PeerApp.Reads,
        ourApp: OurApp = OurApp.Ignores,
        idsAtEstablishment: Int = 0,
    ): Converted {
        // What our endpoint sent and received after it recorded closing is its linger's (RFC 9000 §10.2.1): copies of its
        // close, and datagrams quiche never saw. The schedule's ClosedByUs stands for all of it.
        val closedState = closedState(ourRecording)
        val connection = closedState?.let { closed -> ourRecording.filter { it.at <= closed.at } } ?: ourRecording
        val ourOut = outOf(connection)
        val ourIn = inOf(connection)
        val peerOut = outOf(peerRecording)
        val peerIn = inOf(peerRecording)
        val shared = ourOut.filter { sent -> peerIn.any { it.hex == sent.hex } }
        if (shared.isEmpty()) return Converted.NothingShared
        // The peer's clock plus this is ours: the least it can be, as no datagram arrives before it was sent.
        val skew = shared.maxOf { sent -> sent.at - peerIn.first { it.hex == sent.hex }.at }
        // Every time counts from our first datagram.
        val start = ourOut.first().at
        val offset = skew - start
        val steps = ArrayList<Pair<Duration, Step>>()
        val ourArrivals = matcher(peerIn)
        for (sent in ourOut) {
            val fate =
                when (val arrived = ourArrivals.take(sent.hex)) {
                    null -> Fate.Lost
                    else -> Fate.Delivered(arrived.at + offset)
                }
            val at = sent.at - start
            steps += at to Step.OursSent(at, shapeOf(sent.hex), fate, carriesOf(sent.hex))
        }
        val peerArrivals = matcher(ourIn)
        for (sent in peerOut) {
            val at = sent.at + offset
            when (val arrived = peerArrivals.take(sent.hex)) {
                // A peer datagram stands where our endpoint processed it.
                null -> steps += at to Step.PeerSent(at, shapeOf(sent.hex), Fate.Lost)
                else -> {
                    val processed = arrived.at - start
                    steps += processed to Step.PeerSent(at, shapeOf(sent.hex), Fate.Delivered(processed))
                }
            }
        }
        actions.forEach { recorded ->
            val at = recorded.at - start
            steps += at to Step.OursDid(at, recorded.action)
        }
        // Our endpoint closing is in its recording: the connection state, and the datagram that carried the close.
        val closedAt = closedState?.let { state -> ourOut.lastOrNull { it.at <= state.at } }?.let { it.at - start }
        if (closedAt != null) steps += closedAt to Step.OursDid(closedAt, OurAction.Close)
        val ordered = steps.sortedWith(compareBy({ it.first }, { orderOf(it.second) })).map { it.second }
        val established =
            ourOut.firstOrNull { PacketKind.OneRtt in shapeOf(it.hex) }?.let { Moment.At(it.at - start) } ?: Moment.Never
        val schedule =
            CaptureSchedule(
                name = name,
                ours = ours,
                alpn = alpn,
                ourTransport = ourTransport,
                peerTransport = peerTransport,
                peerPackets = PeerPackets.AsQuiche,
                peerApp = peerApp,
                ourApp = ourApp,
                steps = ordered,
                established = established,
                end = if (closedAt == null) ConnectionEnd.Open else ConnectionEnd.ClosedByUs(closedAt),
                runsFor = ordered.maxOf { it.at },
                tolerance = SendTimeTolerance(),
                idsAtEstablishment = idsAtEstablishment,
                ourTimers = timersOf(connection, start),
            )
        return Converted.Schedule(schedule)
    }

    /** Something our endpoint's application did, which no datagram shows, at [at] on our recording's clock. */
    class RecordedAction(
        val at: Duration,
        val action: OurAction,
    )

    /**
     * Where our endpoint recorded closing the connection itself, when it did. The close travelled in the last datagram
     * it sent at or before then.
     */
    private fun closedState(recording: List<TraceEvent>): TraceEvent.State? =
        recording.filterIsInstance<TraceEvent.State>().firstOrNull { it.name.endsWith(CLOSED) && it.detail?.startsWith(LOCAL) == true }

    /**
     * When our endpoint's host ran quiche's timeout, from our first datagram. The core's flight recorder records every
     * time it runs it, so a recording with none is of a host that ran none: its replay runs none either.
     */
    private fun timersOf(
        recording: List<TraceEvent>,
        start: Duration,
    ): OurTimers = OurTimers.AsRecorded(recording.filterIsInstance<TraceEvent.TimerFired>().map { it.at - start }.sorted())

    /** Takes each recorded datagram once, oldest first, by its bytes. */
    private class Matcher(
        private val byBytes: Map<String, ArrayDeque<Datagram>>,
    ) {
        fun take(hex: String): Datagram? = byBytes[hex]?.removeFirstOrNull()
    }

    private fun matcher(received: List<Datagram>) = Matcher(received.groupBy { it.hex }.mapValues { (_, same) -> ArrayDeque(same) })

    private fun outOf(recording: List<TraceEvent>): List<Datagram> =
        recording.filterIsInstance<TraceEvent.DgramOut>().mapIndexed { i, it -> Datagram(i, it.at, it.payloadHex) }

    private fun inOf(recording: List<TraceEvent>): List<Datagram> =
        recording.filterIsInstance<TraceEvent.DgramIn>().mapIndexed { i, it -> Datagram(i, it.at, it.payloadHex) }

    private fun carriesOf(hex: String): Carries = if (isAcksOnly(shapeOf(hex), hex.length / 2)) Carries.AcksOnly else Carries.Data

    private fun shapeOf(hex: String): List<PacketKind> = packetSpans(bytesOf(hex)).map { it.kind }

    private fun bytesOf(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(HEX).toByte() }

    /** At one instant: what our endpoint did, then what it sent, then what it received. */
    private fun orderOf(step: Step): Int =
        when (step) {
            is Step.OursDid -> 0
            is Step.OursSent -> 1
            is Step.PeerSent -> 2
        }

    private const val HEX = 16
    private const val CLOSED = ".Closed"
    private const val LOCAL = "local"
}
