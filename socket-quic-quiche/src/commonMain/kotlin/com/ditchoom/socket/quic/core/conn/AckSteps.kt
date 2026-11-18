package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.stream.Backlog
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink

/** A datagram of the connection was read. */
internal fun AckCadence.received() =
    when (this) {
        is AckCadence.Immediate -> Unit
        is AckCadence.HoldForReply -> hold.received()
    }

/** The turn has news that needs a write. */
internal fun AckCadence.mustWrite() =
    when (this) {
        is AckCadence.Immediate -> Unit
        is AckCadence.HoldForReply -> hold.mustWrite()
    }

internal fun AckCadence.offerWake(sink: WakeSink) =
    when (this) {
        is AckCadence.Immediate -> Unit
        is AckCadence.HoldForReply -> hold.offerWake(sink)
    }

/** Whether the turn at [now] writes, or holds the ACK quiche owes for the reply ([AckHold]). */
internal fun ConnParts.ackStep(now: Nanos): AckStep =
    when (val ack = slots.ack) {
        is AckCadence.Immediate -> AckStep.Write
        is AckCadence.HoldForReply ->
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
