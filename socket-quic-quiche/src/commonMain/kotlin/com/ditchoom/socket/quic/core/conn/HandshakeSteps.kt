package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicAccessDenial
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.sched.SendRecord
import com.ditchoom.socket.quic.core.time.Nanos
import kotlinx.coroutines.Runnable

/** Whether a serve's handshake leaves the connection open, and why it ends it if not: the end uses this verdict alone. */
internal sealed interface HandshakeServe {
    data object Open : HandshakeServe

    /** The handshake is over; [handshakeEnd] makes its end from this alone. */
    sealed interface Over : HandshakeServe

    /** The system refused every send of it as it refuses an app the access it lacks. */
    class Denied(
        val denial: QuicAccessDenial,
    ) : Over

    /** Its deadline passed. */
    data object DeadlinePassed : Over
}

/**
 * Serves the handshake. Before it completes, a server that accepted 0-RTT announces it once and hands up the streams
 * that arrived in it as [Arrival.Early]; once complete, streams are woken and a client's first session ticket goes up.
 * The report of its completion goes up in [served], after the serve's output.
 */
internal fun ConnParts.handshakeStep(
    now: Nanos,
    paths: Paths,
): HandshakeServe =
    when (handshake.step(now)) {
        HandshakeStep.Handshaking -> {
            early()
            when (val verdict = accessStep()) {
                is AccessVerdict.NotDenied -> HandshakeServe.Open
                is AccessVerdict.Denied -> HandshakeServe.Denied(verdict.denial)
            }
        }
        HandshakeStep.JustEstablished -> {
            paths.established()
            checkOnCompletion()
            established(now)
            HandshakeServe.Open
        }
        HandshakeStep.Established -> {
            established(now)
            HandshakeServe.Open
        }
        HandshakeStep.DeadlinePassed -> HandshakeServe.DeadlinePassed
    }

/**
 * What a handshake still under way whose every datagram the network refused says about the app's access to its peer,
 * from what the setup lane settled: nothing is read here. A handshake that completed was sent to, so a refusal after it
 * never denies the connect.
 */
internal fun ConnParts.accessStep(): AccessVerdict =
    when (handshake.reached()) {
        Progress.Handshaking -> access.judge(sends.view())
        Progress.Established -> AccessVerdict.NotDenied
    }

/**
 * The end of a serve after which the connection is open. A handshake that completed in it is reported now, after its
 * output: quiche sizes the first packets it sends after the handshake to its path MTU probe and those during it to the
 * current MTU, and the datagram room is read first, because a caller may read it the moment it resumes. The timer is
 * armed when the serve collected everything.
 */
internal fun ConnParts.served(
    now: Nanos,
    paths: Paths,
    collected: Collected,
    fire: Runnable,
) {
    announce(now, paths)
    slots.qlog.served()
    timer.served()
    if (collected == Collected.Everything) rearm(now, fire)
}

/** A handshake that completed and was not reported yet is reported now: at the end of its serve, or before the end. */
internal fun ConnParts.announce(
    now: Nanos,
    paths: Paths,
) {
    when (handshake.announcement()) {
        Announcement.Due -> {
            slots.datagrams.wake(now)
            slots.qlog.established()
            val report = handshake.report(now, paths.read(), outcome())
            sink.established(report)
            handshake.reported(report.facts)
        }
        Announcement.None -> Unit
    }
}

/**
 * The end of a handshake that is [over]: denied the access it needs, as its serve found; else past its deadline, sent
 * to and never answered, or never sent for the network refused each datagram.
 */
internal fun ConnParts.handshakeEnd(
    over: HandshakeServe.Over,
    now: Nanos,
    paths: Paths,
): ConnEnd =
    when (over) {
        is HandshakeServe.Denied -> ConnEnd.AccessDenied(over.denial)
        is HandshakeServe.DeadlinePassed -> {
            val progress = handshake.progress(now, paths.read())
            when (val record = sends.view()) {
                is SendRecord.Refused -> ConnEnd.HandshakeSendRefused(progress, record.last)
                is SendRecord.Quiet, is SendRecord.Taking -> ConnEnd.HandshakeTimedOut(progress)
            }
        }
    }

/** The close point of a handshake that ended with [end]: immediate, with no error. The one place a handshake's end closes. */
internal fun ConnParts.closeHandshake(
    end: ConnEnd,
    now: Nanos,
) = close(CloseChoice.Immediate(QuicError.NoError), end, now)

/**
 * Whether a client's output may leave. A flight held for 0-RTT is released once an operation inside waits on the peer,
 * or the connection is closing: neither can go on without the network. Output held for the server's check
 * ([ClientSession.checkGate]) leaves once the check passed, or with the close.
 */
internal fun ConnParts.firstFlight(): FlightGate =
    when (val r = slots.resumption) {
        is Resumption.Server -> FlightGate.Open
        is Resumption.Client ->
            when (r.session.gate()) {
                FlightGate.Open -> r.session.checkGate(life.view())
                FlightGate.Held -> {
                    if (streams.waitsOnPeer() || life.view() !is Life.Running) r.session.release()
                    r.session.gate()
                }
            }
    }

/** A client's server passed its check: output held for it leaves. */
internal fun ConnParts.serverChecked() =
    when (val r = slots.resumption) {
        is Resumption.Server -> Unit
        is Resumption.Client -> {
            r.session.checked()
            when (val early = r.early) {
                is EarlyPlan.None -> Unit
                is EarlyPlan.Keeping -> early.record.checked(streams)
            }
        }
    }

private fun ConnParts.checkOnCompletion() =
    when (val r = slots.resumption) {
        is Resumption.Server -> Unit
        is Resumption.Client -> r.session.completed()
    }

internal fun ConnParts.releaseFirstFlight() =
    when (val r = slots.resumption) {
        is Resumption.Server -> Unit
        is Resumption.Client -> r.session.release()
    }

/**
 * At the end: a client's kept ticket goes back to its store if no datagram of the connection was ever written, and the
 * bytes it kept of its 0-RTT are freed.
 */
internal fun ConnParts.ticketAtEnd() =
    when (val r = slots.resumption) {
        is Resumption.Server -> Unit
        is Resumption.Client -> {
            r.session.ended(egress.firstOut())
            when (val early = r.early) {
                is EarlyPlan.None -> Unit
                is EarlyPlan.Keeping -> early.record.ended(streams)
            }
        }
    }

/**
 * After each datagram quiche read, before any work runs: a client whose server just settled its 0-RTT frees the bytes
 * it kept, replays them, or ends because the server changed what they relied on.
 */
internal fun ConnParts.settleEarly(now: Nanos): EarlySettle {
    val settled =
        when (val r = slots.resumption) {
            is Resumption.Server -> EarlySettle.Open
            is Resumption.Client ->
                when (val early = r.early) {
                    is EarlyPlan.None -> EarlySettle.Open
                    is EarlyPlan.Keeping -> early.record.settle(r.session, streams, handshake)
                }
        }
    when (settled) {
        is EarlySettle.Open -> Unit
        is EarlySettle.Refused -> close(CloseChoice.Immediate(QuicError.NoError), settled.end, now)
    }
    return settled
}

/** Runs [then] with the end of a refused 0-RTT; the connection is past its close point already. */
internal inline fun EarlySettle.ifRefused(then: (ConnEnd) -> Unit) =
    when (this) {
        is EarlySettle.Open -> Unit
        is EarlySettle.Refused -> then(end)
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
    slots.datagrams.wake(now)
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
