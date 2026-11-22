package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.udp.SendBatch

/**
 * Whether a write collected everything quiche had or the batch filled first, or it ends the connection: the first
 * datagram found another clock and the response is to end, or quiche refused a queued datagram as no live connection does.
 */
internal enum class Wrote { All, BatchFull, ClockMismatch, DatagramRefused }

/** What a serve left after a write that ended as [this]. */
internal fun Wrote.collected(): Collected =
    when (this) {
        Wrote.All, Wrote.ClockMismatch, Wrote.DatagramRefused -> Collected.Everything
        Wrote.BatchFull -> Collected.MoreWaiting
    }

/** Whether a write ends its connection, and with what. */
internal sealed interface WriteFailure {
    data object None : WriteFailure

    class Ends(
        val end: ConnEnd,
    ) : WriteFailure
}

/** Inside: writes the connection's output into [out]: datagrams queued to send go only while it runs. */
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
