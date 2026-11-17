package com.ditchoom.socket.quic.replay

import com.ditchoom.socket.quic.QuicRole
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the network did to one captured connection, and what our endpoint was asked to do on it: the
 * part of a capture that a fresh connection can be put through again (RFC_DETERMINISTIC_SIMULATION.md
 * §4, Tier B). The captured bytes are not here, because a fresh connection's keys cannot read them;
 * each datagram is its place in its direction's order, the packet types it coalesced, and its fate.
 *
 * Every time is an offset from the capture's first datagram. [replayCapture] runs it.
 */
internal class CaptureSchedule(
    val name: String,
    /** Which end of the connection the capture was taken on. */
    val ours: QuicRole,
    val alpn: String,
    val ourTransport: TransportSetup,
    val peerTransport: TransportSetup,
    val peerPackets: PeerPackets,
    /** What the modelled peer's application does with the streams our endpoint writes. */
    val peerApp: PeerApp = PeerApp.Reads,
    val ourApp: OurApp = OurApp.Ignores,
    /**
     * In the order our endpoint took them: a peer datagram stands where our endpoint processed it,
     * which the times of two threads' records do not always say.
     */
    val steps: List<Step>,
    /** When our endpoint completed its handshake in the capture. */
    val established: Moment,
    /** How the captured connection stood at [runsFor]. */
    val end: ConnectionEnd,
    /** The capture's length. */
    val runsFor: Duration,
    val tolerance: SendTimeTolerance,
    /** Spare connection ids our endpoint hands its peer in the pass its handshake completes, before it writes again. */
    val idsAtEstablishment: Int = 0,
    /** When our endpoint's timers fire. */
    val ourTimers: OurTimers = OurTimers.WhenDue,
) {
    /**
     * This schedule up to [at], with the connection still open there: what a peer that had not gone
     * away, or a network that had stopped losing, is replayed from.
     */
    fun cutAt(at: Duration): CaptureSchedule =
        CaptureSchedule(
            name = "$name, cut at $at",
            ours = ours,
            alpn = alpn,
            ourTransport = ourTransport,
            peerTransport = peerTransport,
            peerPackets = peerPackets,
            peerApp = peerApp,
            ourApp = ourApp,
            steps = steps.filter { it.at < at },
            established = if (established is Moment.At && established.at < at) established else Moment.Never,
            end = ConnectionEnd.Open,
            runsFor = at,
            tolerance = tolerance,
            idsAtEstablishment = idsAtEstablishment,
            ourTimers = ourTimers,
        )

    /**
     * This schedule with the peer's datagram number [ordinal], counted in the order the peer sent
     * them, lost. For a datagram the modelled peer cannot stand in for: one whose frames quiche would
     * not have chosen, where receiving quiche's choice would mislead our endpoint more than receiving
     * nothing.
     */
    fun withPeerDatagramLost(ordinal: Int): CaptureSchedule {
        val lost = steps.filterIsInstance<Step.PeerSent>().sortedBy { it.at }.filterIndexed { index, _ -> index == ordinal }
        return CaptureSchedule(
            name = "$name, without the peer's datagram $ordinal",
            ours = ours,
            alpn = alpn,
            ourTransport = ourTransport,
            peerTransport = peerTransport,
            peerPackets = peerPackets,
            peerApp = peerApp,
            ourApp = ourApp,
            steps = steps.map { if (it is Step.PeerSent && it in lost) it.copy(fate = Fate.Lost) else it },
            established = established,
            end = end,
            runsFor = runsFor,
            tolerance = tolerance,
            idsAtEstablishment = idsAtEstablishment,
            ourTimers = ourTimers,
        )
    }
}

/** When our endpoint's timers fire in a replay. */
internal sealed interface OurTimers {
    /** When quiche says they fall due: a capture that does not say when its host ran them. */
    data object WhenDue : OurTimers

    /**
     * At the first of [wakes], the times the capture's host was running our endpoint (a timer, a datagram in or out),
     * at or after quiche says one falls due: a host that stalled ran the timer when it next ran at all, and every timer
     * armed after that one moves with it. Past the last, when due.
     */
    data class AsRecorded(
        val wakes: List<Duration>,
    ) : OurTimers
}

/** A packet's type as its unprotected header gives it (RFC 9000 §17), which is also its packet number space. */
internal enum class PacketKind { Initial, ZeroRtt, Handshake, Retry, OneRtt }

/** What the network did with a datagram. */
internal sealed interface Fate {
    /** It arrived at [at], as sent. */
    data class Delivered(
        val at: Duration,
    ) : Fate

    /**
     * It arrived at [at], and the receiver did not read the coalesced packets at these positions: they
     * were damaged on the way, or the receiver discarded them.
     */
    data class Unread(
        val at: Duration,
        val packets: List<Int>,
    ) : Fate

    data object Lost : Fate
}

internal sealed interface Step {
    val at: Duration

    /** Our endpoint sent a datagram of [shape] at [at]. */
    data class OursSent(
        override val at: Duration,
        val shape: List<PacketKind>,
        val fate: Fate,
        val carries: Carries = Carries.Data,
    ) : Step

    /** The peer sent a datagram of [shape] at [at]. */
    data class PeerSent(
        override val at: Duration,
        val shape: List<PacketKind>,
        val fate: Fate,
    ) : Step

    /** Our endpoint's driver or application acted on the connection at [at]. */
    data class OursDid(
        override val at: Duration,
        val action: OurAction,
    ) : Step
}

/** How the captured peer's packets compare with the modelled peer's, which is quiche with the test certificate. */
internal enum class PeerPackets {
    /** One for one: the peer split its handshake flight over packets as quiche does. */
    AsQuiche,

    /**
     * The peer's handshake flight fitted one packet, with a smaller certificate than the modelled
     * peer's: each of the peer's packets is all the modelled peer wrote in that space on one occasion.
     */
    WholeFlights,
}

/**
 * What our endpoint's application does with the bytes the peer sends back on the streams it wrote. Reading them
 * is what lets quiche widen the peer's flow control and acknowledge, so it changes what our endpoint sends.
 */
internal enum class OurApp {
    /** Nothing: the bytes stay in quiche. */
    Ignores,

    /** It reads them as they arrive, right after each receive pass. */
    Reads,
}

/** What the modelled peer's application does with the bytes it reads from a stream. */
internal enum class PeerApp {
    /** Nothing: it reads them. */
    Reads,

    /** It writes them back on the same stream as it reads them, finishing the stream when the stream finished. */
    Echoes,
}

/** A short-header datagram of at most this many bytes holds acknowledgements and at most a control frame. */
private const val ACKS_ONLY_BYTES = 64

/** Whether a datagram of [shape] and [size] bytes holds nothing the peer must acknowledge in return or a sender retransmit. */
internal fun isAcksOnly(
    shape: List<PacketKind>,
    size: Int,
): Boolean = shape == listOf(PacketKind.OneRtt) && size <= ACKS_ONLY_BYTES

/** What a datagram of ours was for. */
internal enum class Carries {
    /** Something the peer must acknowledge. */
    Data,

    /**
     * Acknowledgements only. Whether one is sent depends on which frames the peer chose to send, which
     * a modelled peer does not reproduce, so a replay that does not send it is still the capture.
     */
    AcksOnly,
}

/** What the code above quiche does to our connection once its handshake completes. */
internal sealed interface OurAction {
    /** Issue the peer [count] more connection IDs. */
    data class IssueConnectionIds(
        val count: Int,
    ) : OurAction

    /** Close the connection. */
    data object Close : OurAction

    /** Write [bytes] bytes to [stream]. */
    data class Write(
        val stream: Long,
        val bytes: Int,
        val end: StreamEnd,
    ) : OurAction
}

internal enum class StreamEnd { Open, Finished }

internal sealed interface Moment {
    data object Never : Moment

    data class At(
        val at: Duration,
    ) : Moment
}

/** How a connection stands. */
internal sealed interface ConnectionEnd {
    data object Open : ConnectionEnd

    data class IdleTimeout(
        val at: Duration,
    ) : ConnectionEnd

    data class ClosedByUs(
        val at: Duration,
    ) : ConnectionEnd

    data class ClosedByPeer(
        val at: Duration,
    ) : ConnectionEnd
}

/** The transport parameters of one end that decide when the other end's timers fire and what it may send. */
internal data class TransportSetup(
    val idleTimeout: Duration = 30.seconds,
    val activeConnectionIdLimit: Long = 8,
    val activeMigration: ActiveMigration = ActiveMigration.Disabled,
    val maxData: Long = 10_485_760,
    val maxStreamDataBidiLocal: Long = 1_048_576,
    val maxStreamDataBidiRemote: Long = 1_048_576,
    val maxStreamDataUni: Long = 1_048_576,
    val maxStreamsBidi: Long = 100,
    val maxStreamsUni: Long = 100,
)

internal enum class ActiveMigration { Allowed, Disabled }

/**
 * How far from its captured time our endpoint may send a datagram and still be the capture's datagram:
 * [floor], plus [perMille] thousandths of the wait since our endpoint last received anything in the
 * capture. Each timer is armed from the send before it, so a chain of timers with nothing received
 * between them drifts by a share of its whole length.
 */
internal data class SendTimeTolerance(
    val floor: Duration = 10.milliseconds,
    val perMille: Int = 50,
) {
    fun after(wait: Duration): Duration = floor + wait * perMille / 1000
}

/** Builds a [CaptureSchedule]; every time is in microseconds from the capture's first datagram. */
internal class CaptureScheduleBuilder(
    private val name: String,
    private val ours: QuicRole,
    private val alpn: String,
) {
    private val steps = mutableListOf<Step>()
    private var ourTransport = TransportSetup()
    private var peerTransport = TransportSetup()
    private var peerPackets = PeerPackets.WholeFlights
    private var peerApp = PeerApp.Reads
    private var ourApp = OurApp.Ignores
    private var established: Moment = Moment.Never
    private var end: ConnectionEnd = ConnectionEnd.Open
    private var runsFor: Duration = Duration.ZERO
    private var tolerance = SendTimeTolerance()

    fun ourTransport(setup: TransportSetup) {
        ourTransport = setup
    }

    fun peerTransport(setup: TransportSetup) {
        peerTransport = setup
    }

    fun peerPackets(packets: PeerPackets) {
        peerPackets = packets
    }

    fun peerApp(app: PeerApp) {
        peerApp = app
    }

    fun ourApp(app: OurApp) {
        ourApp = app
    }

    fun tolerance(tolerance: SendTimeTolerance) {
        this.tolerance = tolerance
    }

    /** Our endpoint sent [shape] at [at]; the peer received it at [arrives]. */
    fun sent(
        at: Long,
        arrives: Long,
        vararg shape: PacketKind,
    ) {
        steps += Step.OursSent(at.microseconds, shape.toList(), Fate.Delivered(arrives.microseconds))
    }

    /** Our endpoint sent [shape] at [at]; the peer received it at [arrives] with the packets at [unreadable] unread. */
    fun sentUnread(
        at: Long,
        arrives: Long,
        unreadable: List<Int>,
        vararg shape: PacketKind,
    ) {
        steps += Step.OursSent(at.microseconds, shape.toList(), Fate.Unread(arrives.microseconds, unreadable))
    }

    /** Our endpoint sent only acknowledgements, in [shape], at [at]; the peer received them at [arrives]. */
    fun sentAcks(
        at: Long,
        arrives: Long,
        vararg shape: PacketKind,
    ) {
        steps += Step.OursSent(at.microseconds, shape.toList(), Fate.Delivered(arrives.microseconds), Carries.AcksOnly)
    }

    /** Our endpoint sent only acknowledgements, in [shape], at [at]; the network lost them. */
    fun sentAcksLost(
        at: Long,
        vararg shape: PacketKind,
    ) {
        steps += Step.OursSent(at.microseconds, shape.toList(), Fate.Lost, Carries.AcksOnly)
    }

    /** Our endpoint sent [shape] at [at]; the network lost it. */
    fun sentLost(
        at: Long,
        vararg shape: PacketKind,
    ) {
        steps += Step.OursSent(at.microseconds, shape.toList(), Fate.Lost)
    }

    /** The peer sent [shape] at [at]; our endpoint received it at [arrives]. */
    fun peerSent(
        at: Long,
        arrives: Long,
        vararg shape: PacketKind,
    ) {
        steps += Step.PeerSent(at.microseconds, shape.toList(), Fate.Delivered(arrives.microseconds))
    }

    /** The peer sent [shape] at [at]; our endpoint received it at [arrives] with the packets at [unreadable] unread. */
    fun peerSentUnread(
        at: Long,
        arrives: Long,
        unreadable: List<Int>,
        vararg shape: PacketKind,
    ) {
        steps += Step.PeerSent(at.microseconds, shape.toList(), Fate.Unread(arrives.microseconds, unreadable))
    }

    /** The peer sent [shape] at [at]; the network lost it. */
    fun peerSentLost(
        at: Long,
        vararg shape: PacketKind,
    ) {
        steps += Step.PeerSent(at.microseconds, shape.toList(), Fate.Lost)
    }

    fun issuedConnectionIds(
        at: Long,
        count: Int,
    ) {
        steps += Step.OursDid(at.microseconds, OurAction.IssueConnectionIds(count))
    }

    fun wrote(
        at: Long,
        stream: Long,
        bytes: Int,
        end: StreamEnd,
    ) {
        steps += Step.OursDid(at.microseconds, OurAction.Write(stream, bytes, end))
    }

    fun closed(at: Long) {
        steps += Step.OursDid(at.microseconds, OurAction.Close)
    }

    fun established(at: Long) {
        established = Moment.At(at.microseconds)
    }

    fun idleTimeout(at: Long) {
        end = ConnectionEnd.IdleTimeout(at.microseconds)
    }

    fun closedByUs(at: Long) {
        end = ConnectionEnd.ClosedByUs(at.microseconds)
    }

    fun closedByPeer(at: Long) {
        end = ConnectionEnd.ClosedByPeer(at.microseconds)
    }

    fun runsFor(length: Long) {
        runsFor = length.microseconds
    }

    fun build(): CaptureSchedule =
        CaptureSchedule(
            name = name,
            ours = ours,
            alpn = alpn,
            ourTransport = ourTransport,
            peerTransport = peerTransport,
            peerPackets = peerPackets,
            peerApp = peerApp,
            ourApp = ourApp,
            steps = steps.toList(),
            established = established,
            end = end,
            runsFor = steps.fold(runsFor) { latest, step -> maxOf(latest, step.at) },
            tolerance = tolerance,
        )
}

internal fun captureSchedule(
    name: String,
    ours: QuicRole,
    alpn: String,
    block: CaptureScheduleBuilder.() -> Unit,
): CaptureSchedule = CaptureScheduleBuilder(name, ours, alpn).apply(block).build()
