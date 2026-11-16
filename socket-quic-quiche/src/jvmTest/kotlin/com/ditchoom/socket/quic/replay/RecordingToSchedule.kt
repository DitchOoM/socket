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
 * loopback or in-memory link). The four-tuple each record carries must match too, so a datagram on another path is not
 * mistaken for this one; the schedule itself has no path, so a recording that changed path is refused.
 */
internal object RecordingToSchedule {
    /** What a conversion made. */
    sealed interface Converted {
        class Schedule(
            val schedule: CaptureSchedule,
        ) : Converted

        /** No datagram of ours reached the peer, so the clocks cannot be aligned. */
        data object NothingShared : Converted

        /** A recording holds datagrams on more than one path. */
        data object PathChanged : Converted
    }

    private class Datagram(
        val ordinal: Int,
        val at: Duration,
        val path: Any?,
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
        actions: List<Step.OursDid> = emptyList(),
    ): Converted {
        val ourOut = outOf(ourRecording)
        val ourIn = inOf(ourRecording)
        val peerOut = outOf(peerRecording)
        val peerIn = inOf(peerRecording)
        if ((ourOut + ourIn).distinctBy { it.path }.size > 1) return Converted.PathChanged
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
            steps += at to Step.OursSent(at, shapeOf(sent.hex), fate)
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
        actions.forEach { steps += it.at to it }
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
                steps = ordered,
                established = established,
                end = ConnectionEnd.Open,
                runsFor = ordered.maxOf { it.at },
                tolerance = SendTimeTolerance(),
            )
        return Converted.Schedule(schedule)
    }

    /** Takes each recorded datagram once, oldest first, by its bytes. */
    private class Matcher(
        private val byBytes: Map<String, ArrayDeque<Datagram>>,
    ) {
        fun take(hex: String): Datagram? = byBytes[hex]?.removeFirstOrNull()
    }

    private fun matcher(received: List<Datagram>) = Matcher(received.groupBy { it.hex }.mapValues { (_, same) -> ArrayDeque(same) })

    private fun outOf(recording: List<TraceEvent>): List<Datagram> =
        recording.filterIsInstance<TraceEvent.DgramOut>().mapIndexed { i, it -> Datagram(i, it.at, it.path, it.payloadHex) }

    private fun inOf(recording: List<TraceEvent>): List<Datagram> =
        recording.filterIsInstance<TraceEvent.DgramIn>().mapIndexed { i, it -> Datagram(i, it.at, it.path, it.payloadHex) }

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
}
