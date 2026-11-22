package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.CloseOutcome
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Ending
import com.ditchoom.socket.quic.core.life.Flushed
import com.ditchoom.socket.quic.core.life.Holding
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.life.LifeStep
import com.ditchoom.socket.quic.core.life.PeerAddress
import com.ditchoom.socket.quic.core.life.errorCode
import com.ditchoom.socket.quic.core.life.flightCode
import com.ditchoom.socket.quic.core.path.Outgoing
import com.ditchoom.socket.quic.core.stream.Side
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch
import kotlinx.coroutines.Runnable

/** Retries the streams quiche lets go on, and hands up the ones the peer opened, as [arrival]. */
internal fun ConnParts.wakeStreams(arrival: Arrival) {
    streams.wake()
    streams.takeIncoming { sink.incoming(it, arrival) }
}

/**
 * The close point, at [now], in the connection's order of work: the close [choice] asks for begins, to end with [end],
 * and from here no stream accepts data. What was accepted before it is delivered as [choice] says.
 */
internal fun ConnParts.close(
    choice: CloseChoice,
    end: ConnEnd,
    now: Nanos,
) = life.close(choice, end, now, streams::closePoint)

/**
 * The start of the end, inside: [end] is recorded once and the answer says how a close asked of the connection went.
 * A handshake that completed in the serve that ends the connection is reported first, so an established connection is
 * always reported as such; that report runs before the end is recorded, so a throw in it is contained like any
 * work's and the end still follows. The deadline goes, and the end goes into the flight recording.
 */
internal fun ConnParts.ending(
    now: Nanos,
    end: ConnEnd,
): Ending {
    if (life.view() is Life.Over) return Ending.Already
    announce(now, slots.paths)
    val ending = life.end(end)
    when (ending) {
        is Ending.First -> {
            timer.disarm()
            recordEnd(now, end)
        }
        is Ending.Already -> Unit
    }
    return ending
}

/** The end of the end, inside: the sink publishes it first, so a resumed caller can read it; then work fails. */
internal fun ConnParts.ended(
    end: ConnEnd,
    held: HeldReads,
    outcome: CloseOutcome,
) {
    sink.ended(end, held, outcome, handshake.fate())
    inbox.shut(end) { it.gone(end) }
    streams.endAll(end)
    slots.datagrams.endAll(end)
    flight.toTrace()
}

/**
 * How the connection ended once quiche [said][LifeStep] it closed or drains. A drain, the peer's CONNECTION_CLOSE having
 * arrived, ends it for its callers now, while it keeps its ids until quiche's draining time.
 */
internal fun ConnParts.quicheEnded(
    now: Nanos,
    quiche: LifeStep,
    paths: Paths,
): ConnEnd {
    when (quiche) {
        LifeStep.Draining -> life.drains(timer.drainEnds(now))
        LifeStep.Open, LifeStep.Closed -> Unit
    }
    return paths.endOf(network.endOf(life.quicheEnd()))
}

/**
 * A flush is judged after the serve's write, so what the close point queued (a stream's reset) leaves before the
 * CONNECTION_CLOSE; a flush that is over writes the close into [out] at once. [LifeStep.Closed] when handing quiche the
 * close closed it with nothing to write.
 */
internal fun ConnParts.flushStep(
    now: Nanos,
    out: SendBatch,
    paths: Paths,
): LifeStep =
    when (life.flushStep(now)) {
        Flushed.Waiting -> LifeStep.Open
        Flushed.Handed -> LifeStep.Open.also { writeOut(out, paths) }
        Flushed.Closed -> LifeStep.Closed
    }

/** Whether the end reads out what quiche holds for the streams, or only notes which were live. */
internal enum class HoldAtEnd { ReadOut, LiveOnly }

/**
 * At the end, with quiche still alive: what it holds for every stream still being received, for the reads after the
 * end, a reader parked on one among them. A handshake that never completed holds nothing, as what it received may be a
 * 0-RTT replay. Allocates; once per connection.
 */
internal fun ConnParts.heldAtEnd(how: HoldAtEnd): HeldReads {
    val held = HashMap<Long, HeldStream>()
    val live = HashSet<Long>()
    val readOut = how == HoldAtEnd.ReadOut && handshake.reached() == Progress.Established
    streams.forEachEntry { entry ->
        live.add(entry.id.id)
        if (readOut && entry.received is Side.Open) {
            val one = endHold.stream(entry.id)
            if (one.owed() == Owed.Yes) held[entry.id.id] = one
        }
    }
    return HeldReads(held, live)
}

/** At the end: a client's unused ticket goes back, and the end goes into the flight recording. */
internal fun ConnParts.recordEnd(
    now: Nanos,
    end: ConnEnd,
) {
    ticketAtEnd()
    flight.ended(now, end.flightCode(), end.errorCode())
    flight.keepEnded(role, end)
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
            is Outgoing.To -> egress.sendDroppedTo(out, to.target, flight)
            is Outgoing.Dropped -> Unit
        }
    }
    when (egress.last()) {
        LastOut.Kept -> {
            val confirmation = handshake.confirmation()
            val address =
                when (confirmation) {
                    // Before confirmation the peer's address may not be validated (RFC 9000 §8.1): no unprompted sends.
                    Confirmation.Pending -> PeerAddress.Unvalidated
                    Confirmation.Confirmed -> slots.paths.address()
                }
            life.linger.closeOut(out, egress.closeDatagrams(out, confirmation), now, until, address)
        }
        LastOut.Dropped, LastOut.None -> life.linger.silent(until)
    }
    return CloseSent.Yes
}

/**
 * Serves a connection that has ended: sends the close it still owes, answers late datagrams with it, repeats it as its
 * linger says, and lets go once the linger is over. Only its own deadline brings it back; quiche's timers no longer
 * drive it.
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
    linger.repeatDue(now)
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
    // The connection has ended already: a refused write leaves no close to keep, which is all a refusal changes here.
    egress.write(out, paths, flight, Datagrams.Off)
    if (keepClose(now, out) == CloseSent.No && life.quicheClosed()) life.linger.done()
}
