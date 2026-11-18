package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink

/**
 * What a turn does with the ACK quiche owes: write it now, with whatever else goes; let it wait for work queued in this
 * loop pass; or hold it across turns for a reply.
 */
internal enum class AckStep { Write, UntilPassEnds, UntilReply }

/** Why a turn may not hold its ACK, or [Open] when it may. */
internal enum class HoldGate {
    Open,

    /** The handshake is not confirmed: its packets are acknowledged at once (RFC 9000 §13.2.1). */
    Handshaking,

    /** The connection is closing. */
    Closing,

    /** A path is being validated, or the peer is on more than one address. */
    PathMoving,

    /** A write or an open waits for room the peer's acknowledgements give. */
    Backlogged,

    /** Loss or reordering was seen lately (RFC 9000 §13.2.1). */
    LossRecent,
}

/** What a turn's own counters say: write now, or ask the gate whether a hold may begin or go on. */
internal enum class AckAsk { Write, Gate }

/** How a hold ended: the reply carried its ACK, or the ACK left without one. */
internal enum class HoldEnd { Hit, Miss }

/** Whether an ended hold's deadline may still be armed. */
internal enum class Stale { No, Yes }

/** Whether a turn's own news needs a write whatever the hold says: a deadline fell due, or a path moved. */
private enum class Forced { No, Yes }

/** How far the connection trusts that a reply follows what it hands up. */
private sealed interface Standing {
    /** Holds when a reply is likely; [misses] holds in a row ended without one. */
    class Trusted : Standing {
        var misses = 0
    }

    /** Acknowledges at once; after [skip] more reply-likely turns one trial hold; [span] is the next skip after a trial that misses. */
    class BackedOff : Standing {
        var skip = 0
        var span = 0
    }
}

/** Whether an ACK is held now. */
private sealed interface HoldPhase {
    data object Open : HoldPhase

    /** Held until [deadlineNanos]; [datagrams] arrived since it began. */
    class AckHeld : HoldPhase {
        var deadlineNanos = 0L
        var datagrams = 0
    }
}

/**
 * The ACK hold of one connection. A turn that received a datagram and handed what it carried to the application holds
 * the ACK quiche owes instead of writing it alone, so the reply the application is likely to write carries it in the
 * same datagram. The hold ends at the reply, at its deadline, or at any news that needs a write; it lasts at most
 * [AckRule.AcrossTurns.cap], within the max_ack_delay the connection advertises, and at most a share of the
 * smoothed RTT. Adaptive: a connection whose holds end without a reply [MISSES_TO_BACK_OFF] times in a row backs off and
 * acknowledges at once, trying one hold again after a number of reply-likely turns that doubles with each trial that
 * misses; any reply that lands inside a hold restores it.
 */
@Confined
internal class AckHold(
    private val rule: AckRule.AcrossTurns,
) {
    private val trusted = Standing.Trusted()
    private val backedOff = Standing.BackedOff()
    private val held = HoldPhase.AckHeld()
    private var standing: Standing = trusted
    private var phase: HoldPhase = HoldPhase.Open
    private var received = 0
    private var forced = Forced.No
    private var seenHanded = 0L
    private var seenOutput = 0L
    private var asking = 0
    private var stale = Stale.No

    /** Holds begun, and holds that ended with a reply, over the connection's life. */
    var holds = 0L
        private set
    var hits = 0L
        private set

    /** Quiche read a datagram of this connection. */
    fun received() {
        received++
    }

    /** The turn has news that needs a write: quiche's timer, a move or a network change. */
    fun mustWrite() {
        forced = Forced.Yes
    }

    /**
     * The turn at [now], before its write, from the monotonic counts of received data [handed] up to the application
     * and of [output] handed to quiche to send. [AckAsk.Gate] asks [gated] to decide.
     */
    fun decide(
        now: Nanos,
        handed: Long,
        output: Long,
    ): AckAsk {
        val newlyHanded = handed != seenHanded
        val replied = output != seenOutput
        seenHanded = handed
        seenOutput = output
        val datagrams = received
        received = 0
        val wasForced = forced
        forced = Forced.No
        return when (val p = phase) {
            is HoldPhase.AckHeld -> {
                p.datagrams += datagrams
                when {
                    replied -> ended(HoldEnd.Hit)
                    wasForced == Forced.Yes || now.value >= p.deadlineNanos || p.datagrams > HOLD_DATAGRAMS -> ended(HoldEnd.Miss)
                    else -> return AckAsk.Gate
                }
                AckAsk.Write
            }
            is HoldPhase.Open ->
                when {
                    replied || wasForced == Forced.Yes || !newlyHanded -> AckAsk.Write
                    datagrams == 0 || datagrams > HOLD_DATAGRAMS -> AckAsk.Write
                    else -> AckAsk.Gate.also { asking = datagrams }
                }
        }
    }

    /** After [decide] asked: whether the ACK is held at [now], given the connection's [gate] and smoothed [rttNanos]. */
    fun gated(
        now: Nanos,
        gate: HoldGate,
        rttNanos: Long,
    ): AckStep =
        when (phase) {
            is HoldPhase.AckHeld ->
                when (gate) {
                    HoldGate.Open -> AckStep.UntilReply
                    HoldGate.Handshaking, HoldGate.Closing, HoldGate.PathMoving, HoldGate.Backlogged, HoldGate.LossRecent -> {
                        ended(HoldEnd.Miss)
                        AckStep.Write
                    }
                }
            is HoldPhase.Open ->
                when (gate) {
                    HoldGate.Open -> trial(now, rttNanos)
                    HoldGate.Handshaking, HoldGate.Closing, HoldGate.PathMoving, HoldGate.Backlogged, HoldGate.LossRecent -> AckStep.Write
                }
        }

    /** A hold begins unless the connection backed off and its next trial is not due yet. */
    private fun trial(
        now: Nanos,
        rttNanos: Long,
    ): AckStep {
        when (val s = standing) {
            is Standing.Trusted -> Unit
            is Standing.BackedOff ->
                if (s.skip > 0) {
                    s.skip--
                    return AckStep.Write
                }
        }
        val length = minOf(rule.cap.nanos, rttNanos / rule.rttShare)
        if (length <= 0L) return AckStep.Write
        held.deadlineNanos = now.value + length
        held.datagrams = asking
        phase = held
        holds++
        return AckStep.UntilReply
    }

    /** Offers the hold's deadline while an ACK is held. */
    fun offerWake(sink: WakeSink) =
        when (val p = phase) {
            is HoldPhase.Open -> Unit
            is HoldPhase.AckHeld -> sink.offer(Nanos(p.deadlineNanos))
        }

    /** Whether a hold ended since the last call: its deadline may still be armed. */
    fun deadlineLeft(): Stale = stale.also { stale = Stale.No }

    private fun ended(how: HoldEnd) {
        phase = HoldPhase.Open
        stale = Stale.Yes
        when (how) {
            HoldEnd.Hit -> {
                hits++
                trusted.misses = 0
                standing = trusted
            }
            HoldEnd.Miss ->
                when (val s = standing) {
                    is Standing.Trusted ->
                        if (++s.misses >= MISSES_TO_BACK_OFF) {
                            backedOff.span = FIRST_SKIP
                            backedOff.skip = FIRST_SKIP
                            standing = backedOff
                        }
                    is Standing.BackedOff -> {
                        s.span = minOf(s.span * 2, MAX_SKIP)
                        s.skip = s.span
                    }
                }
        }
    }

    internal companion object {
        /** Holds in a row that end without a reply before the connection acknowledges at once. */
        const val MISSES_TO_BACK_OFF = 4

        /** Reply-likely turns acknowledged at once before the first trial hold; doubled by each trial that misses. */
        const val FIRST_SKIP = 8

        const val MAX_SKIP = 64

        /** More datagrams than this in one hold are acknowledged at once (RFC 9000 §13.2.2: after two ack-eliciting packets). */
        const val HOLD_DATAGRAMS = 2
    }
}
