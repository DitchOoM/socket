package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.sched.SendRecord
import com.ditchoom.socket.quic.core.time.Nanos

/**
 * Serves the handshake. Before it completes, a server that accepted 0-RTT announces it once and hands up the streams
 * that arrived in it as [Arrival.Early]; at completion the report goes up; once complete, streams are woken and a
 * client's first session ticket goes up.
 */
internal fun ConnParts.handshakeStep(
    now: Nanos,
    paths: Paths,
): HandshakeStep {
    val step = handshake.step(now)
    when (step) {
        HandshakeStep.Handshaking -> early()
        HandshakeStep.JustEstablished -> {
            paths.established()
            // Before the caller is told: it may read the datagram room the moment it resumes.
            slots.datagrams.wake()
            sink.established(handshake.report(now, paths.read(), outcome()))
            established(now)
        }
        HandshakeStep.Established -> established(now)
        HandshakeStep.DeadlinePassed -> Unit
    }
    return step
}

/** The end of a handshake that passed its deadline: sent to and never answered, or never sent for the network refused each datagram. */
internal fun ConnParts.handshakeEnd(
    now: Nanos,
    paths: Paths,
): ConnEnd {
    val progress = handshake.progress(now, paths.read())
    return when (val record = sends.view()) {
        is SendRecord.Refused -> ConnEnd.HandshakeSendRefused(progress, record.last)
        is SendRecord.Quiet, is SendRecord.Taking -> ConnEnd.HandshakeTimedOut(progress)
    }
}

/**
 * Whether a client's first flight may leave. A flight held for 0-RTT is released once an operation inside waits on the
 * peer, or the connection is closing: neither can go on without the network.
 */
internal fun ConnParts.firstFlight(): FlightGate =
    when (val r = slots.resumption) {
        is Resumption.Server -> FlightGate.Open
        is Resumption.Client ->
            when (r.session.gate()) {
                FlightGate.Open -> FlightGate.Open
                FlightGate.Held -> {
                    if (streams.waitsOnPeer() || life.view() !is Life.Running) r.session.release()
                    r.session.gate()
                }
            }
    }

internal fun ConnParts.releaseFirstFlight() =
    when (val r = slots.resumption) {
        is Resumption.Server -> Unit
        is Resumption.Client -> r.session.release()
    }

/** At the end: a client's kept ticket goes back to its store if no datagram of the connection was ever written. */
internal fun ConnParts.ticketAtEnd() =
    when (val r = slots.resumption) {
        is Resumption.Server -> Unit
        is Resumption.Client -> r.session.ended(egress.firstOut())
    }

private fun ConnParts.early() =
    when (val r = slots.resumption) {
        is Resumption.Client -> Unit
        is Resumption.Server ->
            when (r.session.step()) {
                EarlyStep.None -> Unit
                EarlyStep.JustAccepted -> {
                    sink.earlyData(EarlyReport(handshake.alpn(), r.session.outcome()))
                    wakeStreams(Arrival.Early)
                }
                EarlyStep.Accepted -> wakeStreams(Arrival.Early)
            }
    }

private fun ConnParts.established(now: Nanos) {
    wakeStreams(Arrival.Confirmed)
    slots.datagrams.wake()
    slots.keepAlive.step(now)
    when (val r = slots.resumption) {
        is Resumption.Server -> Unit
        is Resumption.Client -> {
            r.session.takeTicket(sink)
            r.session.takeToken()
        }
    }
}

private fun ConnParts.outcome(): QuicResumptionOutcome =
    when (val r = slots.resumption) {
        is Resumption.Client -> r.session.outcome()
        is Resumption.Server -> r.session.outcome()
    }
