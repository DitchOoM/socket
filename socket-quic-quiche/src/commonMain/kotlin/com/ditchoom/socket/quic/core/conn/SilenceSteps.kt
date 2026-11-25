package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.path.PathSilence
import com.ditchoom.socket.quic.core.path.ProbeStep
import com.ditchoom.socket.quic.core.path.Unrecorded
import com.ditchoom.socket.quic.core.time.Nanos

// A client's silence watch ([PathSilence]), served from the connection's turn and timer.

/** quiche's timer fired at [now]: a client's silence watch reads the active path's expiries. */
internal fun Paths.timerFired(now: Nanos) =
    when (this) {
        is Paths.Client -> paths.silence.sample(now)
        is Paths.Server -> Unit
    }

/**
 * Inside, each serve of a client at [now]: the ack-eliciting data handed to quiche opens an owed answer, a verdict that
 * fell due is declared, the awaiting-peer probe is sent when due, and the news is recorded in the flight.
 */
internal fun ConnParts.watchSilence(
    now: Nanos,
    silence: PathSilence,
) {
    silence.handed(now, streams.io.output + slots.datagrams.sent() + slots.keepAlive.pinged())
    silence.verdict(now)
    when (silence.probe(now, streams.reads.waiting, streams.handedUp)) {
        ProbeStep.Rested -> Unit
        ProbeStep.Pinged -> {
            slots.ack.mustWrite()
            flight.path(now, PathMark.Pinged)
        }
    }
    when (silence.unrecorded()) {
        Unrecorded.None -> Unit
        Unrecorded.Silent -> flight.path(now, PathMark.Silent)
        Unrecorded.Answering -> flight.path(now, PathMark.Answering)
    }
}

/** Monotonic: the datagrams quiche took to send. */
internal fun Datagrams.sent(): Long =
    when (this) {
        is Datagrams.Off -> 0L
        is Datagrams.On -> out.output
    }
