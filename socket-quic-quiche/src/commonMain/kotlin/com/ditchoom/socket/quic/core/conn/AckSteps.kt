package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.stream.Backlog
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink

/** A datagram of the connection was read. */
internal fun AckCadence.received() =
    when (this) {
        is AckCadence.Immediate -> Unit
        is AckCadence.UntilPassEnds -> hold.received()
        is AckCadence.AcrossTurns -> hold.received()
    }

/** The turn has news that needs a write. */
internal fun AckCadence.mustWrite() =
    when (this) {
        is AckCadence.Immediate, is AckCadence.UntilPassEnds -> Unit
        is AckCadence.AcrossTurns -> hold.mustWrite()
    }

/** Whether a hold that ended left its deadline armed, taken once: the timer is armed afresh for what is due. */
internal fun AckCadence.deadlineLeft(): Stale =
    when (this) {
        is AckCadence.Immediate, is AckCadence.UntilPassEnds -> Stale.No
        is AckCadence.AcrossTurns -> hold.deadlineLeft()
    }

internal fun AckCadence.offerWake(sink: WakeSink) =
    when (this) {
        is AckCadence.Immediate, is AckCadence.UntilPassEnds -> Unit
        is AckCadence.AcrossTurns -> hold.offerWake(sink)
    }

/** Whether the turn at [now] writes, or lets the ACK quiche owes wait for a reply ([SamePassHold], [AckHold]). */
internal fun ConnParts.ackStep(now: Nanos): AckStep =
    when (val ack = slots.ack) {
        is AckCadence.Immediate -> AckStep.Write
        is AckCadence.UntilPassEnds -> ack.hold.decide(streams.handedUp, streams.io.output + slots.datagrams.output())
        is AckCadence.AcrossTurns ->
            when (ack.hold.decide(now, streams.handedUp, streams.io.output + slots.datagrams.output())) {
                AckAsk.Write -> AckStep.Write
                AckAsk.Gate -> ack.hold.gated(now, holdGate(now), ackReads.smoothedRttNanos())
            }
    }

/** Why the connection may not hold its ACK at [now], or [HoldGate.Open]. */
private fun ConnParts.holdGate(now: Nanos): HoldGate {
    if (life.view() !is Life.Running) return HoldGate.Closing
    if (handshake.confirmation() == Confirmation.Pending) return HoldGate.Handshaking
    if (slots.paths.calm() == PathCalm.Moving) return HoldGate.PathMoving
    if (streams.backlog() == Backlog.Waiting) return HoldGate.Backlogged
    return when (ackReads.loss(now)) {
        LossSeen.Quiet -> HoldGate.Open
        LossSeen.Recent -> HoldGate.LossRecent
    }
}

/** Monotonic: datagrams quiche took to send; none when datagrams are off. */
private fun Datagrams.output(): Long =
    when (this) {
        is Datagrams.Off -> 0L
        is Datagrams.On -> box.output
    }

/** What a turn whose ACK waits leaves: a serve again in this pass, or nothing until the reply or the deadline. */
internal fun AckStep.collected(): Collected =
    when (this) {
        AckStep.UntilPassEnds -> Collected.MoreWaiting
        AckStep.Write, AckStep.UntilReply -> Collected.Everything
    }
