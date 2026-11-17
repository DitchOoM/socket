package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.Holding
import com.ditchoom.socket.quic.core.life.LifeStep
import com.ditchoom.socket.quic.core.path.Outgoing
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch
import kotlinx.coroutines.Runnable

/** Retries the streams quiche lets go on, and hands up the ones the peer opened, as [arrival]. */
internal fun ConnParts.wakeStreams(arrival: Arrival) {
    streams.wake()
    streams.takeIncoming { sink.incoming(it, arrival) }
}

/** Whether a write carried the close's CONNECTION_CLOSE. */
internal enum class CloseSent { Yes, No }

/**
 * After a write while the close is with quiche: once quiche drains, that write's last datagram carried the
 * CONNECTION_CLOSE, which is kept to answer late datagrams until quiche's draining time.
 */
internal fun ConnParts.keepClose(
    now: Nanos,
    out: SendBatch,
): CloseSent {
    if (!life.closeSent()) return CloseSent.No
    val until = timer.drainEnds(now)
    if (egress.last() == LastOut.Dropped) {
        when (val to = slots.paths.closeRedirect()) {
            is Outgoing.To -> egress.sendDroppedTo(out, to.target, now, flight)
            is Outgoing.Dropped -> Unit
        }
    }
    when (egress.last()) {
        LastOut.Kept -> life.linger.closeOut(out, egress.lastOffset(), egress.lastLength(), egress.lastTarget(), until)
        LastOut.Dropped, LastOut.None -> life.linger.silent(until)
    }
    return CloseSent.Yes
}

/**
 * Serves a connection that has ended: sends the close it still owes, answers late datagrams with it, and lets go
 * once its linger is over. Only its own deadline brings it back; quiche's timers no longer drive it.
 */
internal fun ConnParts.over(
    now: Nanos,
    out: SendBatch,
    paths: Paths,
    fire: Runnable,
): Collected {
    val linger = life.linger
    if (linger.sending()) sendClose(now, out, paths)
    val before = out.pending
    val room = linger.answer(out)
    for (n in before until out.pending) flight.datagramOut(now, out.storage, out.offsetOf(n), out.lengthOf(n))
    if (linger.due(now)) life.settle()
    when (linger.holds()) {
        Holding.Quiche -> rearm(now, fire)
        Holding.Ids -> {
            linger.offerWake(timer.sink)
            timer.armOffered(fire)
        }
        Holding.Nothing -> {
            life.settle()
            timer.disarm()
        }
    }
    flight.toTrace()
    return if (room == Room.Full) Collected.MoreWaiting else Collected.Everything
}

/** The close an ended connection still owes: quiche writes it now, or has closed without one. */
private fun ConnParts.sendClose(
    now: Nanos,
    out: SendBatch,
    paths: Paths,
) {
    timer.expire()
    egress.write(out, paths, now, flight)
    if (keepClose(now, out) == CloseSent.No && life.observe() == LifeStep.Closed) life.linger.done()
}
