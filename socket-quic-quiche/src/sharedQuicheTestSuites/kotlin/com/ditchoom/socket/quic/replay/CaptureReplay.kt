package com.ditchoom.socket.quic.replay

import com.ditchoom.socket.quic.FlowControl
import com.ditchoom.socket.quic.IncomingStreams
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.MigrationSimEnv
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamLimit
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuichePairByHand
import com.ditchoom.socket.quic.migrationSimOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

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

/**
 * A timer of our endpoint that fell due in the replay after the capture's firing that stood nearest before it, so it ran at
 * [ranAt], the capture's next firing, not at its [due] time: the replay's deadline stood later than the capture's.
 */
internal data class TimerMissed(
    val due: Duration,
    val recorded: Duration,
    val ranAt: Duration,
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
    /** How many acknowledgements the replay sent that the capture holds no datagram for, and that were set aside. */
    val acksUncaptured: Int,
    val ranFor: Duration,
    /** Our timers that missed the capture's firing of them, in the order they fell due. */
    val timersThatMissed: List<TimerMissed> = emptyList(),
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
            if (acksUncaptured > 0) appendLine("  replayed acknowledgements the capture holds no datagram for: $acksUncaptured")
            timersThatMissed.forEach {
                appendLine("  timer due ${it.due} missed the recorded firing ${it.recorded}, so ran at ${it.ranAt}")
            }
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
            is Divergence.OurActionRefused -> "quiche answered ${divergence.code} to ${divergence.action}"
            is Divergence.EndsDifferently ->
                "our connection is ${divergence.replayed}, the capture's ${divergence.captured}"
            is Divergence.NeverSettles -> "still producing events after $REPLAY_EVENT_LIMIT"
        }
}

/**
 * Put a fresh quiche pair through [schedule] on a virtual clock.
 *
 * Our endpoint runs as it would anywhere: its timers fire as [CaptureSchedule.ourTimers] says, and each datagram it
 * sends is checked against the capture's next one for our direction, by the packet types it coalesces
 * and by its time. A match takes the captured datagram's fate. The first mismatch is the replay's
 * [Divergence], and from there no captured fate is applied to anything.
 *
 * The peer is modelled: a quiche endpoint that receives what the captured peer received, when it did,
 * and transmits only when the capture shows the peer transmitting. Its packets stand in for the
 * captured peer's in order within each packet number space, so a peer that coalesces differently from
 * quiche is still followed. It answers what reached it when the captured peer next sent, not on arrival: the
 * acknowledgement delay it reports, which our endpoint takes off its round trip, is then the captured peer's.
 * A captured peer datagram with nothing waiting to stand in for it is a
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
            ),
        incomingStreams = IncomingStreams.Accept(QuicStreamLimit(maxStreamsBidi.toUInt()), QuicStreamLimit(maxStreamsUni.toUInt())),
    )

private class CaptureReplay(
    private val pair: QuichePairByHand,
    private val schedule: CaptureSchedule,
    private val beyond: Beyond,
) {
    private enum class Side { Ours, Peer }

    private enum class PeerState { Answering, Gone }

    /** Whether the modelled peer has answered everything that reached it, or holds input until the captured peer next sends. */
    private enum class PeerInput { Answered, Held }

    /** Whether our endpoint's timers run and its output is taken, or it is still working on what it was handed. */
    private enum class OurEndpoint { Free, Busy }

    private class InFlight(
        val arrives: Duration,
        /** When the capture has it arrive: the replay's own time of arrival is never earlier. */
        val captured: Duration,
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

    private fun Written.isAcknowledgement(): Boolean = isAcksOnly(shape, bytes.size)

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
    private var acksUncaptured = 0
    private val timersThatMissed = LinkedHashSet<TimerMissed>()
    private val sent = mutableListOf<SentDatagram>()
    private val receipts = mutableMapOf<Long, StreamReceipt>()

    /** The capture's time of our endpoint's last receipt, which a send's tolerance is measured from. */
    private var anchor = Duration.ZERO
    private var established: Moment = Moment.Never
    private var end: ConnectionEnd = ConnectionEnd.Open
    private var peerHeardFirst: Moment = Moment.Never
    private var peerState = PeerState.Answering
    private var peerInput = PeerInput.Answered
    private var phase: Phase = Phase.OnCapture
    private var events = 0
    private var ourWork = OurEndpoint.Free

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
            acksUncaptured = acksUncaptured,
            ranFor = now,
            timersThatMissed = timersThatMissed.toList(),
        )
    }

    /** When the capture has the step after the next one happen: our endpoint's, or the arrival of the peer's. */
    private fun nextStepAt(): Duration =
        when (val following = schedule.steps.getOrNull(stepNext + 1)) {
            null -> Duration.INFINITE
            is Step.PeerSent ->
                when (val fate = following.fate) {
                    Fate.Lost -> following.at
                    is Fate.Delivered -> fate.at
                    is Fate.Unread -> fate.at
                }
            else -> following.at
        }

    /**
     * The capture's next step is a datagram of ours: it is the next one written, or the one our timers
     * write in time. A captured datagram of acknowledgements alone may go unsent, and is not waited for past the
     * capture's next step: our timers must not run on to produce what the capture has our endpoint doing later.
     */
    private fun expect(step: Step.OursSent) {
        if (ourWork == OurEndpoint.Busy) answer()
        val tolerance = schedule.tolerance.after(step.at - anchor)
        val waitsUntil =
            when (step.carries) {
                Carries.Data -> step.at + tolerance
                Carries.AcksOnly -> minOf(step.at + tolerance, nextStepAt())
            }
        while (true) {
            if (written.isEmpty()) background(until = waitsUntil)
            if (phase != Phase.OnCapture) return
            val head = written.firstOrNull() ?: break
            if (step.carries != Carries.Data || !head.isAcknowledgement()) break
            // An acknowledgement where the capture has data is one the capture does not hold.
            written.removeFirst()
            acksUncaptured++
        }
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

    /**
     * The capture's next step is a peer datagram: one our endpoint never saw, or the first of a receive pass. Our
     * endpoint sends only after a pass has taken everything already queued, so the datagrams of one pass, those
     * that follow each other within [RECEIVE_PASS] with nothing of ours sent between, are delivered together.
     */
    private fun receive(step: Step.PeerSent) {
        if (step.fate == Fate.Lost) return skip()
        if (!nothingUncaptured()) return
        val pass = receivePass()
        if (!runUntil(pass.members.last().arrives)) return
        // The peer answers what our endpoint sent however late it was, so its datagrams exist before they are delivered.
        if (!runUntil(maxOf(schedule.runsFor, now)) { pass.members.all { it.step in emissions } }) return
        stepNext += pass.length
        anchor = pass.members.last().arrives
        val datagrams =
            pass.members.flatMap { member ->
                when (val emission = emissions.getOrElse(member.step) { Emission.Nothing }) {
                    Emission.Nothing -> emptyList()
                    is Emission.Datagrams -> emission.datagrams
                }
            }
        if (datagrams.isNotEmpty()) {
            pair.deliver(datagrams.map { QuichePairByHand.Datagram(it, PRIMARY_ENDPOINT) }, ours)
            if (schedule.ourApp == OurApp.Reads) receipts.keys.forEach { pair.readText(ours, QuicStreamId(it)) }
            noteOurState()
            answer()
        }
    }

    private class Arrival(
        val step: Step.PeerSent,
        val arrives: Duration,
    )

    /** The delivered peer datagrams of one receive pass, and the steps they and the lost ones among them take. */
    private class Pass(
        val members: List<Arrival>,
        val length: Int,
    )

    /** The peer datagrams, from the next step on, that one receive pass of our endpoint takes. */
    private fun receivePass(): Pass {
        val pass = mutableListOf<Arrival>()
        var length = 0
        for (step in schedule.steps.drop(stepNext)) {
            if (step !is Step.PeerSent) break
            val arrives =
                when (val fate = step.fate) {
                    Fate.Lost -> {
                        length++
                        continue
                    }
                    is Fate.Delivered -> fate.at
                    is Fate.Unread -> fate.at
                }
            if (pass.isNotEmpty() && arrives - pass.last().arrives > RECEIVE_PASS) break
            pass += Arrival(step, arrives)
            length++
        }
        return Pass(pass, length)
    }

    /**
     * Run our endpoint up to [until], setting aside the acknowledgements it writes on the way. False when the replay
     * left the capture, or our endpoint ended, before it got there.
     */
    private fun runUntil(
        until: Duration,
        ready: () -> Boolean = { false },
    ): Boolean {
        while (true) {
            background(until, ready)
            if (phase != Phase.OnCapture || !nothingUncaptured()) return false
            if (now >= until || ready() || end != ConnectionEnd.Open) return true
        }
    }

    private fun skip() {
        stepNext++
    }

    private fun perform(step: Step.OursDid) {
        if (!nothingUncaptured()) return
        if (!runUntil(step.at)) return
        stepNext++
        act(step.action)
        // A close ends the connection at the action itself: nothing else that happens later need be there to see it.
        noteOurEnd()
        answer()
    }

    /**
     * Takes what our endpoint wrote in answer to what it was just handed when the capture's endpoint sent it. quiche stamps
     * its packets as it writes them, and a slow host writes them milliseconds after it read what they answer, so writing
     * them sooner would move every timer armed from them. When the capture's next step is a datagram of ours, our endpoint
     * writes at its time; when it is more input, our endpoint takes that first, as the capture's did before it sent
     * anything. Meanwhile the network and the peer go on, and our endpoint's timers wait for its work, as a busy one's do.
     *
     * A recorded timer that fires before that write ran in the same work, after the read and before the write: the
     * capture's endpoint wrote nothing between, so what it wrote holds both the answer and what the timeout made.
     */
    private fun answer() {
        when (val next = schedule.steps.drop(stepNext).firstOrNull { !(it is Step.PeerSent && it.fate == Fate.Lost) }) {
            is Step.OursSent -> {
                val fires = recordedTimerFires()
                if (fires < next.at) {
                    if (fires > now) background(fires, endpoint = OurEndpoint.Busy)
                    if (phase != Phase.OnCapture || end != ConnectionEnd.Open) return free()
                    fireOurTimer()
                    if (ourWork == OurEndpoint.Busy) return
                }
                if (next.at > now) background(next.at, endpoint = OurEndpoint.Busy)
                free()
            }
            is Step.PeerSent, is Step.OursDid -> ourWork = OurEndpoint.Busy
            null -> free()
        }
    }

    /**
     * Our timeout just ran: what it made is written when the capture's endpoint wrote it, when that is the capture's next
     * datagram of ours, with nothing between but our endpoint's own actions, and no recorded timer runs before it. A host
     * can stall between running quiche's timeout and writing its probe, and quiche stamps the probe as it writes it, so
     * writing it sooner would move every timer armed from it. An action between is taken in the same turn, before the
     * write, as a host whose loop ran the timeout and the application's work in one pass did: our endpoint stays busy
     * until the action is performed.
     */
    private fun writeWhenCaptured() {
        val ahead = schedule.steps.drop(stepNext).filterNot { it is Step.PeerSent && it.fate == Fate.Lost }
        val actions = ahead.takeWhile { it is Step.OursDid }.size
        val next = ahead.getOrNull(actions)
        if (next !is Step.OursSent || (actions == 0 && next.at <= now)) return
        val firings =
            when (val timers = schedule.ourTimers) {
                OurTimers.WhenDue -> return
                is OurTimers.AsRecorded -> timers.firings
            }
        if (firings.any { it > now + TIMER_PRECISION && it < next.at }) return
        if (actions == 0) background(next.at, endpoint = OurEndpoint.Busy) else ourWork = OurEndpoint.Busy
    }

    /** quiche's timeout runs once, as the core's does: what it re-arms overdue fires after its probes are sent. */
    private fun fireOurTimer() {
        if (timerAt(ours) > now + TIMER_PRECISION) pair.bringTimerForward(ours) else pair.runTimeout(ours)
        writeWhenCaptured()
    }

    /** When our endpoint's next timer fires on a capture whose host's firings are recorded; never, on one whose are not. */
    private fun recordedTimerFires(): Duration =
        when (schedule.ourTimers) {
            OurTimers.WhenDue -> Duration.INFINITE
            is OurTimers.AsRecorded -> ourTimerFires()
        }

    /** Our endpoint's work is done: its timers run again and what it wrote is taken. */
    private fun free() {
        ourWork = OurEndpoint.Free
        collectOurs()
    }

    /**
     * Whether every datagram our endpoint has written is one the capture has accounted for. The replay
     * leaves the capture at one that is not.
     */
    private fun nothingUncaptured(): Boolean {
        // An acknowledgement the capture does not hold, where it holds no datagram of ours, is not the capture's to account for.
        while (written.firstOrNull()?.isAcknowledgement() == true && schedule.steps.getOrNull(stepNext) !is Step.OursSent) {
            written.removeFirst()
            acksUncaptured++
        }
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
        // The captured peer sent nothing more: what reached the modelled peer since, it reads now.
        answerHeld()
        free()
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
     * written a datagram, or has ended. A [OurEndpoint.Busy] endpoint's timers do not fire and its output stays in quiche.
     */
    private fun background(
        until: Duration,
        ready: () -> Boolean = { false },
        endpoint: OurEndpoint = ourWork,
    ) {
        while (phase == Phase.OnCapture && end == ConnectionEnd.Open && !ready()) {
            if (++events > REPLAY_EVENT_LIMIT) return leave(Divergence.NeverSettles(now))
            val arrival = nextArrival()
            val peerSend = peerSends.drop(peerNext).take(1).filterNot { awaitsOurs(it) }
            val arrivalAt = arrival.fold(Duration.INFINITE) { _, flight -> flight.arrives }
            val peerSendAt = peerSend.fold(Duration.INFINITE) { _, step -> step.at }
            val timerAt =
                when (endpoint) {
                    OurEndpoint.Free -> ourTimerFires()
                    OurEndpoint.Busy -> Duration.INFINITE
                }
            val next = minOf(arrivalAt, peerSendAt, timerAt)
            if (next > until) {
                advanceTo(until, emptyList())
                return
            }
            advanceTo(next, emptyList())
            when (next) {
                arrivalAt -> arrive(arrival.first())
                peerSendAt -> {
                    peerNext++
                    peerSends(peerSend.first(), peerNext - 1)
                }
                // quiche's timeout runs once, as the core's does: what it re-arms overdue fires after its probes are sent.
                else -> {
                    fireOurTimer()
                    // The timeout's turn holds an action of ours: what it made is written once that is performed.
                    if (ourWork == OurEndpoint.Busy) return
                }
            }
            when (endpoint) {
                OurEndpoint.Free -> {
                    noteOurState()
                    collectOurs()
                }
                OurEndpoint.Busy -> Unit
            }
            if (written.isNotEmpty()) return
        }
    }

    private fun runHealthy(healthy: Beyond.HealthyPath) {
        // What the modelled peer was never asked for was never sent; a live peer retransmits by itself.
        standIns.values.forEach { it.clear() }
        answerHeld()
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

    /**
     * Whether a datagram of ours that the capture has reach the peer before it sent [send] has yet to reach the modelled
     * peer: the peer's datagram answers it, so the modelled peer has it first, however late the replay sent it.
     */
    private fun awaitsOurs(send: Step.PeerSent): Boolean {
        val unsent =
            schedule.steps.drop(stepNext).filterIsInstance<Step.OursSent>().any {
                capturedArrival(it.fate)?.let { at -> at <= send.at } ==
                    true
            }
        return unsent || inFlight.any { it.to == Side.Peer && it.captured <= send.at }
    }

    private fun capturedArrival(fate: Fate): Duration? =
        when (fate) {
            Fate.Lost -> null
            is Fate.Delivered -> fate.at
            is Fate.Unread -> fate.at
        }

    /** The datagram in flight that arrives first, if any is in flight. */
    private fun nextArrival(): List<InFlight> = inFlight.sortedWith(compareBy<InFlight> { it.arrives }.thenBy { it.order }).take(1)

    /**
     * When our endpoint's next timer fires: when it falls due, or when the capture's host next ran a timer from then on;
     * never on the capture when its host ran none from then on. A firing within [TIMER_PRECISION] before it is the one the
     * capture's timer fired at, a hair before the replay's is due.
     *
     * When the replay's timer falls due past the last recorded firing, it is brought forward to the next one still ahead:
     * the core records a firing only when quiche's own timer was due, so the capture's was due then. The replay's estimate of the path
     * stands on recorded times, and quiche subtracts the peer's ACK delay from an RTT sample only when the sample is at
     * least the minimum RTT plus that delay (RFC 9002 §5.3): a delay within microseconds of that edge, which recorded times
     * cannot settle, moves the PTO by the whole delay.
     */
    private fun ourTimerFires(): Duration {
        val due = timerAt(ours)
        return when (val timers = schedule.ourTimers) {
            OurTimers.WhenDue -> due
            is OurTimers.AsRecorded -> {
                // The recording holds every timer its host ran: one that ran none after this fell due ran none at all.
                val next =
                    timers.firings.firstOrNull { it >= due - TIMER_PRECISION }
                        ?: return timers.firings.firstOrNull { it > now && due.isFinite() } ?: Duration.INFINITE
                val fires = maxOf(due, next)
                val missed = timers.firings.lastOrNull { it < due - TIMER_PRECISION }
                if (due.isFinite() && missed != null && fires > due) timersThatMissed += TimerMissed(due, missed, fires)
                fires
            }
        }
    }

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
                    when (phase) {
                        Phase.OnCapture -> peerInput = PeerInput.Held
                        is Phase.Beyond -> answerAtPeer()
                    }
                }
        }
    }

    /** The modelled peer writes what it has to say to what reached it, then reads it, then writes again. */
    private fun answerAtPeer() {
        peerInput = PeerInput.Answered
        collectPeer()
        readAtPeer()
        collectPeer()
    }

    private fun answerHeld() =
        when (peerInput) {
            PeerInput.Held -> answerAtPeer()
            PeerInput.Answered -> Unit
        }

    private fun noteOurState() {
        if (established == Moment.Never && pair.established(ours)) {
            established = Moment.At(now)
            if (schedule.idsAtEstablishment > 0) pair.issueSpareCids(ours, atMost = schedule.idsAtEstablishment)
        }
        noteOurEnd()
    }

    /** How our connection ended, if it has: read only, so it moves neither establishment nor the ids issued with it. */
    private fun noteOurEnd() {
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
            if (schedule.peerApp == PeerApp.Echoes && (text.isNotEmpty() || finished)) {
                pair.streamSend(peer, QuicStreamId(receipt.stream), text, finished)
            }
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
            is Fate.Delivered -> inFlight += InFlight(maxOf(fate.at, now), fate.at, flightOrder++, to, bytes)
            is Fate.Unread -> inFlight += InFlight(maxOf(fate.at, now), fate.at, flightOrder++, to, damage(bytes, fate.packets))
        }
    }

    private fun sendHealthy(
        bytes: ByteArray,
        to: Side,
    ) {
        when (beyond) {
            Beyond.Stop -> Unit
            is Beyond.HealthyPath -> inFlight += InFlight(now + beyond.oneWay, now + beyond.oneWay, flightOrder++, to, bytes)
        }
    }

    /** Take what the modelled peer has to send now: stand-ins while the capture is followed, datagrams after. */
    private fun collectPeer() {
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
                PeerPackets.AsQuiche -> packets.forEach { (kind, packet) -> standIns.getValue(kind).addLast(listOf(packet)) }
                PeerPackets.WholeFlights ->
                    packets.groupBy({ it.first }, { it.second }).forEach { (kind, flight) -> standIns.getValue(kind).addLast(flight) }
            }
        } else {
            datagrams.forEach { sendHealthy(it.bytes, Side.Ours) }
        }
    }

    /** The captured peer sends [step] now: the modelled peer's next flight of each packet type stands in for it. */
    private fun peerSends(
        step: Step.PeerSent,
        ordinal: Int,
    ) {
        answerHeld()
        if (step.fate != Fate.Lost) retransmitFor(step.shape)
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

    /**
     * Bring the modelled peer's loss timer forward until it holds a stand-in for each of [shape], if it will. A timer run
     * here stands in for what the captured peer sent now: the probes it wrote beyond that are lost on the way, so none of
     * them stands in later for a datagram the captured peer wrote after reading more of ours.
     */
    private fun retransmitFor(shape: List<PacketKind>) {
        val wanted = shape.groupingBy { it }.eachCount()
        val held = standIns.mapValues { (kind, waiting) -> maxOf(waiting.size, wanted[kind] ?: 0) }
        repeat(PEER_RETRANSMISSIONS) {
            if (wanted.all { (kind, count) -> standIns.getValue(kind).size >= count }) return trimTo(held)
            when (pair.bringTimerForward(peer)) {
                QuichePairByHand.Timer.Unarmed -> return trimTo(held)
                is QuichePairByHand.Timer.DueIn -> collectPeer()
            }
        }
        trimTo(held)
    }

    private fun trimTo(held: Map<PacketKind, Int>) =
        standIns.forEach { (kind, waiting) -> while (waiting.size > held.getValue(kind)) waiting.removeLast() }

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

        /**
         * How far the replay's deadlines may stand from the capture's: quiche's own clock reads differ from the
         * capture's by microseconds; a host's stall lasts milliseconds.
         */
        val TIMER_PRECISION = 1.milliseconds

        /** Datagrams that reach our endpoint this close together are one receive pass: it takes what is queued. */
        val RECEIVE_PASS = 250.microseconds

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
