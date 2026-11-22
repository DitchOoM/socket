package com.ditchoom.socket.quic.core.conn

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

/** Whether a write ends its connection, and the failure it met. */
internal sealed interface WriteFailure {
    data object None : WriteFailure

    class Ends(
        val failure: Throwable,
    ) : WriteFailure
}
