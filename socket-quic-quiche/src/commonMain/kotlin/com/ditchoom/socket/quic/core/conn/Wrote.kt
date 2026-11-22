package com.ditchoom.socket.quic.core.conn

/**
 * Whether a write collected everything quiche had or the batch filled first, or it ends the connection: the first
 * datagram found another clock and the response is to end, quiche refused to write ([Egress.refusal]), or quiche
 * refused a queued datagram as no live connection does ([Egress.datagramRefusal]).
 */
internal enum class Wrote { All, BatchFull, ClockMismatch, Refused, DatagramRefused }

/** What a serve left after a write that ended as [this]. */
internal fun Wrote.collected(): Collected =
    when (this) {
        Wrote.All, Wrote.ClockMismatch, Wrote.Refused, Wrote.DatagramRefused -> Collected.Everything
        Wrote.BatchFull -> Collected.MoreWaiting
    }
