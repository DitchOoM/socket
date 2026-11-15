package com.ditchoom.socket.quic.replay

import com.ditchoom.socket.quic.FlowControl
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.MigrationSimEnv
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuichePairByHand
import com.ditchoom.socket.quic.migrationSimOptions
import kotlin.time.Duration

/**
 * What happens once the replay has no capture left to follow: the capture ran out, or the fresh
 * connection left it.
 */
internal sealed interface Beyond {
    /** The replay stops there. */
    data object Stop : Beyond

    /**
     * The network delivers every datagram [oneWay] after it is sent, and the peer answers as a live
     * endpoint on its own timers, for [runFor].
     */
    data class HealthyPath(
        val oneWay: Duration,
        val runFor: Duration,
        val peer: PeerPatience = PeerPatience.Unbounded,
    ) : Beyond
}

/** How long the modelled peer keeps a connection whose handshake has not completed. */
internal sealed interface PeerPatience {
    data object Unbounded : PeerPatience

    /**
     * The peer drops the connection, sending nothing, when its handshake is not complete [limit] after
     * the first datagram it received.
     */
    data class HandshakeWithin(
        val limit: Duration,
    ) : PeerPatience
}

/** Where a fresh connection stopped being the captured one. */
internal sealed interface Divergence {
    val at: Duration

    /** Our endpoint's datagram number [ordinal] coalesced other packet types than the capture's. */
    data class OurDatagramDiffers(
        override val at: Duration,
        val ordinal: Int,
        val captured: Step.OursSent,
        val replayed: List<PacketKind>,
    ) : Divergence

    /** Our endpoint sent datagram number [ordinal] further from its captured time than [tolerance]. */
    data class OurDatagramMistimed(
        override val at: Duration,
        val ordinal: Int,
        val captured: Step.OursSent,
        val tolerance: Duration,
    ) : Divergence

    /** Our endpoint had not sent datagram number [ordinal] when its captured time had passed. */
    data class OurDatagramMissing(
        override val at: Duration,
        val ordinal: Int,
        val captured: Step.OursSent,
    ) : Divergence

    /** Our endpoint sent a datagram after the capture's last. */
    data class OurDatagramUncaptured(
        override val at: Duration,
        val ordinal: Int,
        val replayed: List<PacketKind>,
    ) : Divergence

    /** A captured action fell due before our endpoint had completed its handshake. */
    data class OurActionBeforeHandshake(
        override val at: Duration,
        val action: OurAction,
    ) : Divergence

    /** The modelled peer's quiche refused a write the captured peer made, with [code]. */
    data class PeerWriteRefused(
        override val at: Duration,
        val write: PeerWrite,
        val code: Int,
    ) : Divergence

    /** quiche refused a captured action with [code]. */
    data class OurActionRefused(
        override val at: Duration,
        val action: OurAction,
        val code: Int,
    ) : Divergence

    /** Our connection stood otherwise than the capture's when the capture ended. */
    data class EndsDifferently(
        override val at: Duration,
        val captured: ConnectionEnd,
        val replayed: ConnectionEnd,
    ) : Divergence

    /** The replay was still producing events after [REPLAY_EVENT_LIMIT] of them. */
    data class NeverSettles(
        override val at: Duration,
    ) : Divergence
}

internal sealed interface Course {
    /** The fresh connection sent what the capture's did, when it did, and ended as it did. */
    data object FollowedCapture : Course

    data class LeftCapture(
        val divergence: Divergence,
    ) : Course
}

/** A datagram our endpoint sent in the replay. */
internal data class SentDatagram(
    val at: Duration,
    val shape: List<PacketKind>,
)

/** What the peer's application read of a stream our endpoint wrote. */
internal data class StreamReceipt(
    val stream: Long,
    val bytes: Int,
    val finished: Moment,
)

/** A captured peer datagram the modelled peer had no packet of these types for; our endpoint received the rest of it. */
internal data class StandInGap(
    val ordinal: Int,
    val absent: List<PacketKind>,
)

internal class ReplayReport(
    val schedule: CaptureSchedule,
    val course: Course,
    /** When our endpoint completed its handshake. */
    val established: Moment,
    val end: ConnectionEnd,
    val sent: List<SentDatagram>,
    val peerRead: List<StreamReceipt>,
    val standInGaps: List<StandInGap>,
    /** The captured datagrams of ours, by number, that held only acknowledgements and that the replay did not send. */
    val acksNotSent: List<Int>,
    val ranFor: Duration,
) {
    /**
     * Whether the replay was the captured connection at least until [at]. A replay that leaves the
     * capture sooner has not put our endpoint through the captured scenario, so nothing it shows
     * afterwards is evidence about that scenario.
     */
    fun followedCaptureUntil(at: Duration): Boolean =
        when (course) {
            Course.FollowedCapture -> true
            is Course.LeftCapture -> course.divergence.at >= at
        }

    /** Whether the peer's application had read [stream] to its end by [at]. */
    fun peerReadToTheEndBy(
        stream: Long,
        at: Duration,
    ): Boolean =
        peerRead.any { receipt ->
            when (val finished = receipt.finished) {
                Moment.Never -> false
                is Moment.At -> receipt.stream == stream && finished.at <= at
            }
        }

    fun describe(): String =
        buildString {
            appendLine("replay of \"${schedule.name}\", ${ranFor.inWholeMilliseconds} ms:")
            when (course) {
                Course.FollowedCapture -> appendLine("  followed the capture to its end")
                is Course.LeftCapture -> appendLine("  LEFT THE CAPTURE at ${course.divergence.at}: ${describe(course.divergence)}")
            }
            appendLine("  established: replay $established, capture ${schedule.established}")
            appendLine("  end: replay $end, capture ${schedule.end}")
            peerRead.forEach { appendLine("  peer read stream ${it.stream}: ${it.bytes} bytes, finished ${it.finished}") }
            standInGaps.forEach { appendLine("  peer datagram ${it.ordinal}: the modelled peer had no ${it.absent} packet") }
            val captured = schedule.steps.filterIsInstance<Step.OursSent>()
            if (acksNotSent.isNotEmpty()) appendLine("  captured acknowledgements not sent: our datagrams $acksNotSent")
            appendLine("  our datagrams, replayed:")
            sent.forEachIndexed { i, it -> appendLine("    $i: ${it.at.inWholeMicroseconds} us ${it.shape}") }
            appendLine("  our datagrams, captured:")
            captured.forEachIndexed { i, it -> appendLine("    $i: ${it.at.inWholeMicroseconds} us ${it.shape} ${it.fate} ${it.carries}") }
        }

    private fun describe(divergence: Divergence): String =
        when (divergence) {
            is Divergence.OurDatagramDiffers ->
                "our datagram ${divergence.ordinal} is ${divergence.replayed}, the capture's is " +
                    "${divergence.captured.shape} at ${divergence.captured.at}"
            is Divergence.OurDatagramMistimed ->
                "our datagram ${divergence.ordinal} ${divergence.captured.shape} was sent at ${divergence.at}, the " +
                    "capture's at ${divergence.captured.at}, more than ${divergence.tolerance} apart"
            is Divergence.OurDatagramMissing ->
                "our datagram ${divergence.ordinal} ${divergence.captured.shape}, captured at " +
                    "${divergence.captured.at}, was not sent"
            is Divergence.OurDatagramUncaptured ->
                "our datagram ${divergence.ordinal} ${divergence.replayed} is one more than the capture holds"
            is Divergence.OurActionBeforeHandshake -> "${divergence.action} fell due before our handshake completed"
            is Divergence.PeerWriteRefused -> "the modelled peer's quiche answered ${divergence.code} to ${divergence.write}"
            is Divergence.OurActionRefused -> "quiche answered ${divergence.code} to ${divergence.action}"
            is Divergence.EndsDifferently ->
                "our connection is ${divergence.replayed}, the capture's ${divergence.captured}"
            is Divergence.NeverSettles -> "still producing events after $REPLAY_EVENT_LIMIT"
        }
}

/**
 * Put a fresh quiche pair through [schedule] on a virtual clock.
 *
 * Our endpoint runs as it would anywhere: its timers fire when they fall due, and each datagram it
 * sends is checked against the capture's next one for our direction, by the packet types it coalesces
 * and by its time. A match takes the captured datagram's fate. The first mismatch is the replay's
 * [Divergence], and from there no captured fate is applied to anything.
 *
 * The peer is modelled: a quiche endpoint that receives what the captured peer received, when it did,
 * and transmits only when the capture shows the peer transmitting. Its packets stand in for the
 * captured peer's in order within each packet number space, so a peer that coalesces differently from
 * quiche is still followed. A captured peer datagram with nothing waiting to stand in for it is a
 * retransmission, and the modelled peer's loss timer is brought forward to produce one. What the
 * captured peer stopped doing, the modelled peer stops doing: its own timers never fire.
 *
 * [beyond] says what follows the capture's end or the divergence.
 */
internal suspend fun replayCapture(
    env: MigrationSimEnv,
    schedule: CaptureSchedule,
    beyond: Beyond = Beyond.Stop,
): ReplayReport {
    val oursOptions = schedule.ourTransport.toOptions(schedule.alpn)
    val peerOptions = schedule.peerTransport.toOptions(schedule.alpn)
    val pair =
        when (schedule.ours) {
            QuicRole.Client -> QuichePairByHand.open(env, options = oursOptions, serverOptions = peerOptions)
            QuicRole.Server -> QuichePairByHand.open(env, options = peerOptions, serverOptions = oursOptions)
        }
    try {
        return CaptureReplay(pair, schedule, beyond).run()
    } finally {
        pair.close()
    }
}

private fun TransportSetup.toOptions(alpn: String): QuicOptions =
    migrationSimOptions(
        idleTimeout = idleTimeout,
        migration =
            when (activeMigration) {
                ActiveMigration.Allowed -> MigrationPolicy.Manual
                ActiveMigration.Disabled -> MigrationPolicy.Forbidden
            },
    ).copy(
        alpnProtocols = listOf(alpn),
        activeConnectionIdLimit = activeConnectionIdLimit,
        flowControl =
            FlowControl(
                initialMaxData = maxData,
                initialMaxStreamDataBidiLocal = maxStreamDataBidiLocal,
                initialMaxStreamDataBidiRemote = maxStreamDataBidiRemote,
                initialMaxStreamDataUni = maxStreamDataUni,
                initialMaxStreamsBidi = maxStreamsBidi,
                initialMaxStreamsUni = maxStreamsUni,
            ),
    )

private class CaptureReplay(
    private val pair: QuichePairByHand,
    private val schedule: CaptureSchedule,
    private val beyond: Beyond,
) {
    private enum class Side { Ours, Peer }

    private enum class PeerState { Answering, Gone }

    /** Where the modelled peer's new packets go among those waiting to stand in. */
    private enum class StandInOrder { Last, First }

    private class InFlight(
        val arrives: Duration,
        val order: Int,
        val to: Side,
        val bytes: ByteArray,
    )

    /** A datagram our endpoint has written that the capture has not yet been asked about. */
    private class Written(
        val at: Duration,
        val bytes: ByteArray,
        val shape: List<PacketKind>,
    )

    /**
     * What the modelled peer holds for one packet of a captured peer datagram: every packet of that type
     * it wrote on one occasion. A flight the captured peer fitted in one packet may take the modelled
     * peer several, when its certificate is the larger.
     */
    private sealed interface StandIn {
        class Flight(
            val packets: List<ByteArray>,
        ) : StandIn

        data class Absent(
            val kind: PacketKind,
        ) : StandIn
    }

    /** What the modelled peer sent for a captured peer datagram that reached our endpoint. */
    private sealed interface Emission {
        class Datagrams(
            val datagrams: List<ByteArray>,
        ) : Emission

        data object Nothing : Emission
    }

    private sealed interface Phase {
        data object OnCapture : Phase

        data class Beyond(
            val course: Course,
        ) : Phase
    }

    private val ours = if (schedule.ours == QuicRole.Client) pair.client else pair.server
    private val peer = if (schedule.ours == QuicRole.Client) pair.server else pair.client

    /** The peer's captured datagrams in the order the peer sent them, which is the order their stand-ins are taken in. */
    private val peerSends = schedule.steps.filterIsInstance<Step.PeerSent>().sortedBy { it.at }
    private var stepNext = 0
    private var oursNext = 0
    private var peerNext = 0
    private var flightOrder = 0
    private val inFlight = mutableListOf<InFlight>()
    private val written = ArrayDeque<Written>()
    private val standIns = PacketKind.entries.associateWith { ArrayDeque<List<ByteArray>>() }
    private val emissions = mutableMapOf<Step.PeerSent, Emission>()
    private val standInGaps = mutableListOf<StandInGap>()
    private val acksNotSent = mutableListOf<Int>()
    private val sent = mutableListOf<SentDatagram>()
    private val receipts = mutableMapOf<Long, StreamReceipt>()

    /** The capture's time of our endpoint's last receipt, which a send's tolerance is measured from. */
    private var anchor = Duration.ZERO
    private var established: Moment = Moment.Never
    private var end: ConnectionEnd = ConnectionEnd.Open
    private var peerHeardFirst: Moment = Moment.Never
    private var peerState = PeerState.Answering

    /** How far the modelled peer's clock has been brought forward for retransmissions. */
    private var peerLead = Duration.ZERO
    private var phase: Phase = Phase.OnCapture
    private var events = 0

    private val now: Duration get() = pair.elapsed

    fun run(): ReplayReport {
        collectPeer()
        collectOurs()
        while (phase == Phase.OnCapture && stepNext < schedule.steps.size) {
            when (val step = schedule.steps[stepNext]) {
                is Step.OursSent -> expect(step)
                is Step.PeerSent -> receive(step)
                is Step.OursDid -> perform(step)
            }
        }
        if (phase == Phase.OnCapture) finishCapture()
        when (beyond) {
            Beyond.Stop -> Unit
            is Beyond.HealthyPath -> runHealthy(beyond)
        }
        val course =
            when (val reached = phase) {
                Phase.OnCapture -> Course.FollowedCapture
                is Phase.Beyond -> reached.course
            }
        return ReplayReport(
            schedule = schedule,
            course = course,
            established = established,
            end = end,
            sent = sent.toList(),
            peerRead = receipts.values.sortedBy { it.stream },
            standInGaps = standInGaps.toList(),
            acksNotSent = acksNotSent.toList(),
            ranFor = now,
        )
    }

    /**
     * The capture's next step is a datagram of ours: it is the next one written, or the one our timers
     * write in time. A captured datagram of acknowledgements alone may go unsent.
     */
    private fun expect(step: Step.OursSent) {
        val tolerance = schedule.tolerance.after(step.at - anchor)
        if (written.isEmpty()) background(until = step.at + tolerance)
        if (phase != Phase.OnCapture) return
        val divergence =
            when {
                written.isEmpty() -> Divergence.OurDatagramMissing(now, oursNext, step)
                written.first().shape != step.shape ->
                    Divergence.OurDatagramDiffers(
                        written.first().at,
                        oursNext,
                        step,
                        written.first().shape,
                    )
                (written.first().at - step.at).absoluteValue > tolerance ->
                    Divergence.OurDatagramMistimed(written.first().at, oursNext, step, tolerance)
                else -> {
                    launch(written.removeFirst().bytes, step.fate, Side.Peer)
                    oursNext++
                    stepNext++
                    return
                }
            }
        when (step.carries) {
            Carries.Data -> leave(divergence)
            Carries.AcksOnly -> {
                acksNotSent += oursNext
                oursNext++
                stepNext++
            }
        }
    }

    /** The capture's next step is a peer datagram: one our endpoint never saw, or one it receives now. */
    private fun receive(step: Step.PeerSent) {
        val arrives =
            when (val fate = step.fate) {
                Fate.Lost -> return skip()
                is Fate.Delivered -> fate.at
                is Fate.Unread -> fate.at
            }
        if (!nothingUncaptured()) return
        background(until = arrives)
        if (phase != Phase.OnCapture || !nothingUncaptured()) return
        stepNext++
        anchor = arrives
        when (val emission = emissions.getOrElse(step) { Emission.Nothing }) {
            Emission.Nothing -> Unit
            is Emission.Datagrams -> {
                pair.deliver(emission.datagrams.map { QuichePairByHand.Datagram(it, PRIMARY_ENDPOINT) }, ours)
                noteOurState()
                collectOurs()
            }
        }
    }

    private fun skip() {
        stepNext++
    }

    private fun perform(step: Step.OursDid) {
        if (!nothingUncaptured()) return
        background(until = step.at)
        if (phase != Phase.OnCapture || !nothingUncaptured()) return
        stepNext++
        act(step.action)
        collectOurs()
    }

    /**
     * Whether every datagram our endpoint has written is one the capture has accounted for. The replay
     * leaves the capture at one that is not.
     */
    private fun nothingUncaptured(): Boolean {
        written.take(1).forEach { leave(Divergence.OurDatagramUncaptured(it.at, oursNext, it.shape)) }
        return written.isEmpty()
    }

    /** The capture has no step left: our connection must stand as the capture's did when it stopped. */
    private fun finishCapture() {
        val captured = schedule.end
        val until =
            when (captured) {
                ConnectionEnd.Open -> schedule.runsFor
                is ConnectionEnd.IdleTimeout -> captured.at + schedule.tolerance.after(captured.at - anchor)
                is ConnectionEnd.ClosedByUs -> captured.at + schedule.tolerance.after(captured.at - anchor)
                is ConnectionEnd.ClosedByPeer -> captured.at + schedule.tolerance.after(captured.at - anchor)
            }
        background(until)
        if (phase != Phase.OnCapture || !nothingUncaptured()) return
        val replayed = end
        val agrees =
            when (captured) {
                ConnectionEnd.Open -> replayed == ConnectionEnd.Open
                is ConnectionEnd.IdleTimeout -> replayed is ConnectionEnd.IdleTimeout && near(replayed.at, captured.at)
                is ConnectionEnd.ClosedByUs -> replayed is ConnectionEnd.ClosedByUs && near(replayed.at, captured.at)
                is ConnectionEnd.ClosedByPeer -> replayed is ConnectionEnd.ClosedByPeer && near(replayed.at, captured.at)
            }
        phase =
            Phase.Beyond(if (agrees) Course.FollowedCapture else Course.LeftCapture(Divergence.EndsDifferently(now, captured, replayed)))
    }

    private fun near(
        replayed: Duration,
        captured: Duration,
    ): Boolean = (replayed - captured).absoluteValue <= schedule.tolerance.after(captured - anchor)

    /**
     * Run what happens without a step of ours asking for it, up to [until]: our endpoint's timers, the
     * captured arrivals at the peer, and the peer's captured sends. Returns early once our endpoint has
     * written a datagram, or has ended.
     */
    private fun background(until: Duration) {
        while (phase == Phase.OnCapture && end == ConnectionEnd.Open) {
            if (++events > REPLAY_EVENT_LIMIT) return leave(Divergence.NeverSettles(now))
            val arrival = nextArrival()
            val peerSend = peerSends.drop(peerNext).take(1)
            val arrivalAt = arrival.fold(Duration.INFINITE) { _, flight -> flight.arrives }
            val peerSendAt = peerSend.fold(Duration.INFINITE) { _, step -> step.at }
            val next = minOf(arrivalAt, peerSendAt, timerAt(ours))
            if (next > until) {
                advanceTo(until, listOf(ours))
                return
            }
            advanceTo(next, listOf(ours))
            when (next) {
                arrivalAt -> arrive(arrival.first())
                peerSendAt -> {
                    peerNext++
                    peerSends(peerSend.first(), peerNext - 1)
                }
                else -> Unit
            }
            noteOurState()
            collectOurs()
            if (written.isNotEmpty()) return
        }
    }

    private fun runHealthy(healthy: Beyond.HealthyPath) {
        // What the modelled peer was never asked for was never sent; a live peer retransmits by itself.
        standIns.values.forEach { it.clear() }
        while (written.isNotEmpty()) sendHealthy(written.removeFirst().bytes, Side.Peer)
        val remaining = ArrayDeque(schedule.steps.drop(stepNext).filterIsInstance<Step.OursDid>())
        val until = now + healthy.runFor
        while (end == ConnectionEnd.Open && ++events <= REPLAY_EVENT_LIMIT) {
            val arrival = nextArrival()
            val arrivalAt = arrival.fold(Duration.INFINITE) { _, flight -> flight.arrives }
            val actionAt =
                if (remaining.isNotEmpty() && established is Moment.At) maxOf(remaining.first().at, now) else Duration.INFINITE
            val ourTimerAt = timerAt(ours)
            val peerTimerAt = if (peerState == PeerState.Answering) timerAt(peer) else Duration.INFINITE
            val givesUpAt = peerGivesUpAt(healthy.peer)
            val next = minOf(minOf(arrivalAt, actionAt, ourTimerAt), minOf(peerTimerAt, givesUpAt))
            if (next > until) break
            advanceTo(next, if (peerState == PeerState.Answering) listOf(ours, peer) else listOf(ours))
            when (next) {
                arrivalAt -> arrive(arrival.first())
                actionAt -> act(remaining.removeFirst().action)
                givesUpAt -> peerState = PeerState.Gone
                else -> Unit
            }
            noteOurState()
            collectOurs()
            collectPeer()
        }
    }

    /** The datagram in flight that arrives first, if any is in flight. */
    private fun nextArrival(): List<InFlight> = inFlight.sortedWith(compareBy<InFlight> { it.arrives }.thenBy { it.order }).take(1)

    /** When [conn]'s next timer falls due; never, when none is armed. */
    private fun timerAt(conn: QuicheConn): Duration =
        when (val timer = pair.timer(conn)) {
            QuichePairByHand.Timer.Unarmed -> Duration.INFINITE
            is QuichePairByHand.Timer.DueIn -> now + timer.span
        }

    private fun peerGivesUpAt(patience: PeerPatience): Duration {
        val heard = peerHeardFirst
        return when (patience) {
            PeerPatience.Unbounded -> Duration.INFINITE
            is PeerPatience.HandshakeWithin ->
                if (heard is Moment.At && peerState == PeerState.Answering && !pair.established(peer)) {
                    maxOf(heard.at + patience.limit, now)
                } else {
                    Duration.INFINITE
                }
        }
    }

    private fun advanceTo(
        at: Duration,
        firing: List<QuicheConn>,
    ) {
        pair.elapse(maxOf(at - now, Duration.ZERO), firing)
    }

    private fun arrive(flight: InFlight) {
        inFlight.remove(flight)
        val datagram = QuichePairByHand.Datagram(flight.bytes, PRIMARY_ENDPOINT)
        when (flight.to) {
            Side.Ours -> pair.deliver(listOf(datagram), ours)
            Side.Peer ->
                if (peerState == PeerState.Answering) {
                    if (peerHeardFirst == Moment.Never) peerHeardFirst = Moment.At(now)
                    pair.deliver(listOf(datagram), peer)
                    collectPeer()
                    readAtPeer()
                }
        }
    }

    private fun noteOurState() {
        if (established == Moment.Never && pair.established(ours)) established = Moment.At(now)
        if (end == ConnectionEnd.Open) {
            end =
                when (pair.ending(ours)) {
                    QuichePairByHand.Ending.Open -> ConnectionEnd.Open
                    QuichePairByHand.Ending.IdleTimeout -> ConnectionEnd.IdleTimeout(now)
                    QuichePairByHand.Ending.ClosedLocally -> ConnectionEnd.ClosedByUs(now)
                    QuichePairByHand.Ending.ClosedByPeer -> ConnectionEnd.ClosedByPeer(now)
                }
        }
    }

    private fun leave(divergence: Divergence) {
        if (phase == Phase.OnCapture) phase = Phase.Beyond(Course.LeftCapture(divergence))
    }

    private fun act(action: OurAction) {
        when (action) {
            OurAction.Close -> pair.closeWith(ours, QuicError.NoError)
            is OurAction.IssueConnectionIds -> onceEstablished(action) { pair.issueSpareCids(ours, atMost = action.count) }
            is OurAction.Write -> onceEstablished(action) { write(action) }
        }
    }

    /** Do [action], which quiche takes only from a connection whose handshake has completed. */
    private inline fun onceEstablished(
        action: OurAction,
        act: () -> Unit,
    ) {
        if (established == Moment.Never) leave(Divergence.OurActionBeforeHandshake(now, action)) else act()
    }

    private fun write(action: OurAction.Write) {
        val wrote = pair.streamSend(ours, QuicStreamId(action.stream), "x".repeat(action.bytes), action.end == StreamEnd.Finished)
        if (wrote == action.bytes) {
            receipts.getOrPut(action.stream) { StreamReceipt(action.stream, 0, Moment.Never) }
        } else {
            leave(Divergence.OurActionRefused(now, action, wrote))
        }
    }

    private fun readAtPeer() {
        for (receipt in receipts.values.toList()) {
            if (receipt.finished is Moment.At) continue
            val (text, finished) = pair.readText(peer, QuicStreamId(receipt.stream))
            receipts[receipt.stream] =
                receipt.copy(bytes = receipt.bytes + text.length, finished = if (finished) Moment.At(now) else Moment.Never)
        }
    }

    /** Take what our endpoint has to send now: for the capture to account for, or onto the healthy path. */
    private fun collectOurs() {
        for (datagram in pair.sends(ours)) {
            val shape = packetSpans(datagram.bytes).map { it.kind }
            sent += SentDatagram(now, shape)
            if (phase == Phase.OnCapture) written.addLast(Written(now, datagram.bytes, shape)) else sendHealthy(datagram.bytes, Side.Peer)
        }
    }

    private fun launch(
        bytes: ByteArray,
        fate: Fate,
        to: Side,
    ) {
        when (fate) {
            Fate.Lost -> Unit
            is Fate.Delivered -> inFlight += InFlight(maxOf(fate.at, now), flightOrder++, to, bytes)
            is Fate.Unread -> inFlight += InFlight(maxOf(fate.at, now), flightOrder++, to, damage(bytes, fate.packets))
        }
    }

    private fun sendHealthy(
        bytes: ByteArray,
        to: Side,
    ) {
        when (beyond) {
            Beyond.Stop -> Unit
            is Beyond.HealthyPath -> inFlight += InFlight(now + beyond.oneWay, flightOrder++, to, bytes)
        }
    }

    /** Take what the modelled peer has to send now: stand-ins while the capture is followed, datagrams after. */
    private fun collectPeer(order: StandInOrder = StandInOrder.Last) {
        if (peerState == PeerState.Gone) return
        val datagrams = pair.sends(peer)
        if (phase == Phase.OnCapture) {
            val packets =
                datagrams.flatMap { datagram ->
                    packetSpans(datagram.bytes).map {
                        it.kind to
                            datagram.bytes.copyOfRange(it.from, it.until)
                    }
                }
            when (schedule.peerPackets) {
                PeerPackets.AsQuiche -> packets.reversedIf(order).forEach { (kind, packet) -> queue(kind, listOf(packet), order) }
                PeerPackets.WholeFlights ->
                    packets.groupBy({ it.first }, { it.second }).forEach { (kind, flight) -> queue(kind, flight, order) }
            }
        } else {
            datagrams.forEach { sendHealthy(it.bytes, Side.Ours) }
        }
    }

    /** [packets] in the order that leaves them in their own order once each is queued by [order]. */
    private fun <T> List<T>.reversedIf(order: StandInOrder): List<T> =
        when (order) {
            StandInOrder.Last -> this
            StandInOrder.First -> reversed()
        }

    private fun queue(
        kind: PacketKind,
        flight: List<ByteArray>,
        order: StandInOrder,
    ) {
        when (order) {
            StandInOrder.Last -> standIns.getValue(kind).addLast(flight)
            StandInOrder.First -> standIns.getValue(kind).addFirst(flight)
        }
    }

    /** The captured peer sends [step] now: the modelled peer's next flight of each packet type stands in for it. */
    private fun peerSends(
        step: Step.PeerSent,
        ordinal: Int,
    ) {
        for (write in step.wrote) {
            val wrote = pair.streamSend(peer, QuicStreamId(write.stream), "x".repeat(write.bytes), write.end == StreamEnd.Finished)
            if (wrote != write.bytes) return leave(Divergence.PeerWriteRefused(now, write, wrote))
        }
        // What the peer's application has just written leaves in this datagram, ahead of anything waiting.
        if (step.wrote.isNotEmpty()) collectPeer(StandInOrder.First)
        if (step.fate != Fate.Lost && step.carries == Carries.Data) retransmitFor(step.shape)
        val flights =
            step.shape.map { kind ->
                val waiting = standIns.getValue(kind)
                if (waiting.isEmpty()) StandIn.Absent(kind) else StandIn.Flight(waiting.removeFirst())
            }
        val absent = flights.filterIsInstance<StandIn.Absent>()
        if (step.fate == Fate.Lost) return
        if (absent.isNotEmpty()) standInGaps += StandInGap(ordinal, absent.map { it.kind })
        val unread =
            when (val fate = step.fate) {
                Fate.Lost, is Fate.Delivered -> emptyList()
                is Fate.Unread -> fate.packets
            }
        val packets =
            flights.flatMapIndexed { position, flight ->
                when (flight) {
                    is StandIn.Absent -> emptyList()
                    is StandIn.Flight -> if (position in unread) flight.packets.map { unreadable(it) } else flight.packets
                }
            }
        emissions[step] = Emission.Datagrams(coalesce(packets))
    }

    /** [packets] as datagrams of at most [STAND_IN_DATAGRAM] bytes; a short-header packet ends its datagram. */
    private fun coalesce(packets: List<ByteArray>): List<ByteArray> =
        packets
            .fold(listOf<List<ByteArray>>()) { datagrams, packet ->
                val last = datagrams.takeLast(1).flatten()
                val fits = last.isNotEmpty() && isLongHeader(last.last()) && last.sumOf { it.size } + packet.size <= STAND_IN_DATAGRAM
                if (fits) datagrams.dropLast(1).plusElement(last + listOf(packet)) else datagrams.plusElement(listOf(packet))
            }.map { datagram -> datagram.fold(ByteArray(0)) { bytes, packet -> bytes + packet } }

    private fun isLongHeader(packet: ByteArray): Boolean = packet[0].toInt() and 0x80 != 0

    /** Bring the modelled peer's loss timer forward until it holds a stand-in for each of [shape], if it will. */
    private fun retransmitFor(shape: List<PacketKind>) {
        repeat(PEER_RETRANSMISSIONS) {
            val wanted = shape.groupingBy { it }.eachCount()
            if (wanted.all { (kind, count) -> standIns.getValue(kind).size >= count }) return
            when (val timer = pair.timer(peer)) {
                QuichePairByHand.Timer.Unarmed -> return
                is QuichePairByHand.Timer.DueIn -> {
                    // A timer this far out is not a loss timer; firing it would idle the modelled peer out.
                    if (peerLead + timer.span > schedule.peerTransport.idleTimeout / 2) return
                    peerLead += timer.span
                    pair.bringTimerForward(peer)
                    collectPeer()
                }
            }
        }
    }

    /** [bytes] with the packets at [packets] made unreadable; padding after the last packet stays. */
    private fun damage(
        bytes: ByteArray,
        packets: List<Int>,
    ): ByteArray {
        val damaged = bytes.copyOf()
        packetSpans(bytes).filterIndexed { position, _ -> position in packets }.forEach {
            damaged[it.until - 1] =
                flipped(damaged[it.until - 1])
        }
        return damaged
    }

    /** [packet] with a bit of its authentication tag flipped, so its receiver drops it (RFC 9001 §5.3). */
    private fun unreadable(packet: ByteArray): ByteArray = packet.copyOf().also { it[it.size - 1] = flipped(it[it.size - 1]) }

    private fun flipped(byte: Byte): Byte = (byte.toInt() xor 0x01).toByte()

    private companion object {
        const val PRIMARY_ENDPOINT = 0

        /** Two probes is a full PTO flight; a third firing covers a peer whose first two were its other spaces'. */
        const val PEER_RETRANSMISSIONS = 3

        /** The smallest datagram every QUIC path carries (RFC 9000 §14). */
        const val STAND_IN_DATAGRAM = 1200
    }
}

/** More events than any capture of a connection's handshake and first exchanges holds. */
internal const val REPLAY_EVENT_LIMIT = 100_000

/** A packet coalesced in a datagram: its type and the bytes `[from, until)` it spans. */
internal class PacketSpan(
    val kind: PacketKind,
    val from: Int,
    val until: Int,
)

/**
 * The QUIC version 1 packets coalesced in [datagram], in order, read from their unprotected headers
 * (RFC 9000 §17). A short header always ends a datagram; zero bytes after the last packet are padding;
 * a header that runs past the datagram ends the reading.
 */
internal fun packetSpans(datagram: ByteArray): List<PacketSpan> =
    buildList {
        var at = 0
        while (at < datagram.size) {
            val start = at
            val first = datagram[at].toInt() and 0xFF
            if (first and 0x80 == 0) {
                if (first and 0x40 != 0) add(PacketSpan(PacketKind.OneRtt, start, datagram.size))
                return@buildList
            }
            val kind = LONG_HEADER_KINDS[(first shr 4) and 0x03]
            if (kind == PacketKind.Retry) {
                add(PacketSpan(kind, start, datagram.size))
                return@buildList
            }
            at += 5
            repeat(2) {
                if (at >= datagram.size) return@buildList
                at += 1 + (datagram[at].toInt() and 0xFF)
            }
            if (kind == PacketKind.Initial) {
                when (val token = varint(datagram, at)) {
                    Varint.Truncated -> return@buildList
                    is Varint.Read -> at += token.width + token.value
                }
            }
            when (val length = varint(datagram, at)) {
                Varint.Truncated -> return@buildList
                is Varint.Read -> at += length.width + length.value
            }
            if (at > datagram.size) return@buildList
            add(PacketSpan(kind, start, at))
        }
    }

private val LONG_HEADER_KINDS = listOf(PacketKind.Initial, PacketKind.ZeroRtt, PacketKind.Handshake, PacketKind.Retry)

/** A variable-length integer (RFC 9000 §16) read from a datagram. */
private sealed interface Varint {
    class Read(
        val value: Int,
        val width: Int,
    ) : Varint

    /** It starts at, or runs past, the datagram's end. */
    data object Truncated : Varint
}

private fun varint(
    datagram: ByteArray,
    at: Int,
): Varint {
    if (at < 0 || at >= datagram.size) return Varint.Truncated
    val first = datagram[at].toInt() and 0xFF
    val width = 1 shl (first shr 6)
    if (at + width > datagram.size) return Varint.Truncated
    var value = (first and 0x3F).toLong()
    for (i in 1 until width) value = (value shl 8) or (datagram[at + i].toLong() and 0xFF)
    return Varint.Read(value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), width)
}
