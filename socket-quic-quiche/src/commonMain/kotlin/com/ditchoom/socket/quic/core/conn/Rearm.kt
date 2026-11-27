package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.SendBatch
import kotlinx.coroutines.Runnable

/**
 * The serve's write into [out]: as quiche paces it, unless the connection is not paced. Datagrams queued to send go
 * only while the connection runs.
 */
internal fun ConnParts.writeOut(
    out: SendBatch,
    paths: Paths,
): Wrote {
    val datagrams = datagramsToWrite()
    return when (val pacing = slots.pacing) {
        is Pacing.Unpaced -> egress.write(out, paths, flight, datagrams)
        is Pacing.ByQuiche -> egress.writePaced(out, paths, pacing.pacer, flight, datagrams)
    }
}

/** Offers every component's next wake to the connection's timer, then arms it for the earliest. */
internal fun ConnParts.rearm(
    now: Nanos,
    fire: Runnable,
) {
    when (val pacing = slots.pacing) {
        is Pacing.Unpaced -> Unit
        is Pacing.ByQuiche -> pacing.pacer.offerWake(timer.sink)
    }
    slots.keepAlive.offerWake(timer.sink)
    slots.ack.offerWake(timer.sink)
    handshake.offerWake(timer.sink)
    life.offerWake(timer.sink)
    slots.paths.offerWake(timer.sink, streams.reads.waiting, life.view())
    if (slots.ack.deadlineLeft() == Stale.Yes) timer.disarm()
    timer.rearm(now, fire)
}

/**
 * The connection's deadline fell due at [now]: quiche's timeout runs, and is recorded, when quiche's own timer has fallen
 * due, and a client's silence watch then reads the expiries it left; while unconfirmed the handshake counts its PTO
 * expiries. A deadline armed from a turn's start falls due before quiche's timer does; a replay that ran its timer
 * there would probe where this endpoint did nothing.
 */
internal fun ConnParts.expired(
    now: Nanos,
    paths: Paths,
) {
    when (timer.quicheDue()) {
        TimerDue.Due -> {
            slots.ack.mustWrite()
            flight.timerFired(now)
            timer.expire()
            paths.timerFired(now)
        }
        TimerDue.NotYet -> Unit
    }
    when (handshake.confirmation()) {
        Confirmation.Pending -> handshake.expired(paths.read())
        Confirmation.Confirmed -> Unit
    }
}
