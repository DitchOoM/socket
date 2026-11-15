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
    val serverCertificate: TestCertificate,
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
            serverCertificate = serverCertificate,
            steps = steps.filter { it.at < at },
            established = if (established is Moment.At && established.at < at) established else Moment.Never,
            end = ConnectionEnd.Open,
            runsFor = at,
            tolerance = tolerance,
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
            serverCertificate = serverCertificate,
            steps = steps.map { if (it is Step.PeerSent && it in lost) it.copy(fate = Fate.Lost) else it },
            established = established,
            end = end,
            runsFor = runsFor,
            tolerance = tolerance,
        )
    }
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

    /** The peer sent a datagram of [shape] at [at], the first to carry what its application [wrote]. */
    data class PeerSent(
        override val at: Duration,
        val shape: List<PacketKind>,
        val fate: Fate,
        val wrote: List<PeerWrite> = emptyList(),
        val carries: Carries = Carries.Data,
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
 * Which test certificate the replay's server presents. Its size decides how many packets the server's
 * handshake flight takes, which is part of every datagram shape after it.
 */
internal enum class TestCertificate {
    /** `cert.crt`, RSA: a flight of two Handshake packets. */
    Rsa,

    /** `pinned.crt`, ECDSA P-256: a flight of one Handshake packet, as the interop runner's certificates give. */
    Ecdsa,
}

/** What a datagram was for. */
internal enum class Carries {
    /** Something its receiver must acknowledge. */
    Data,

    /**
     * Acknowledgements only. Whether one is sent depends on which frames the other end chose to send,
     * which a modelled peer does not reproduce: a replay in which our endpoint does not send one is
     * still the capture, and the modelled peer is not made to retransmit to stand in for one.
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

/** [bytes] more bytes of [stream] from the peer's application. */
internal data class PeerWrite(
    val stream: Long,
    val bytes: Int,
    val end: StreamEnd,
)

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
    private var serverCertificate = TestCertificate.Rsa
    private val peerWrites = mutableListOf<PeerWrite>()
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

    fun serverCertificate(certificate: TestCertificate) {
        serverCertificate = certificate
    }

    fun peerPackets(packets: PeerPackets) {
        peerPackets = packets
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

    /** The peer's application wrote [bytes] more bytes of [stream], which leave in the peer's next datagram here. */
    fun peerWrote(
        stream: Long,
        bytes: Int,
        end: StreamEnd,
    ) {
        peerWrites += PeerWrite(stream, bytes, end)
    }

    /** The writes declared since the peer's last datagram, which this one carries. */
    private fun carried(): List<PeerWrite> = peerWrites.toList().also { peerWrites.clear() }

    /** The peer sent [shape] at [at]; our endpoint received it at [arrives]. */
    fun peerSent(
        at: Long,
        arrives: Long,
        vararg shape: PacketKind,
    ) {
        steps += Step.PeerSent(at.microseconds, shape.toList(), Fate.Delivered(arrives.microseconds), carried())
    }

    /** The peer sent only acknowledgements, in [shape], at [at]; our endpoint received them at [arrives]. */
    fun peerSentAcks(
        at: Long,
        arrives: Long,
        vararg shape: PacketKind,
    ) {
        steps += Step.PeerSent(at.microseconds, shape.toList(), Fate.Delivered(arrives.microseconds), carried(), Carries.AcksOnly)
    }

    /** The peer sent [shape] at [at]; our endpoint received it at [arrives] with the packets at [unreadable] unread. */
    fun peerSentUnread(
        at: Long,
        arrives: Long,
        unreadable: List<Int>,
        vararg shape: PacketKind,
    ) {
        steps += Step.PeerSent(at.microseconds, shape.toList(), Fate.Unread(arrives.microseconds, unreadable), carried())
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
            serverCertificate = serverCertificate,
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
