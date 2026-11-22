package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.core.life.ConnEnd

/**
 * Where a [StreamTable]'s connection stands for its streams. Only an [Open] table accepts stream data: past the close
 * point no state holds a [StreamSend]. An operation that starts after the end fails with it.
 */
internal sealed interface Streams {
    /** Reads, and every operation that hands quiche no stream data, go on. */
    sealed interface Live : Streams

    /** Before the close point: [send] accepts stream data. */
    class Open(
        val send: StreamSend,
    ) : Live,
        Sender

    /** Past the close point of a close that ends with [end]: a write or an open is refused with it; reads go on. */
    class Closing(
        val end: ConnEnd,
    ) : Live

    class Ended(
        val end: ConnEnd,
    ) : Streams
}

/**
 * What [sender] answers every operation that would hand quiche stream data, a write, a FIN or an open:
 * the open table's sender, or why nothing may be sent.
 */
internal sealed interface Sender {
    class Refused(
        val why: SendRefusal,
    ) : Sender
}

/** Why a [StreamTable] accepts no stream data. */
internal sealed interface SendRefusal {
    /** The connection passed the close point of a close that ends with [end]. */
    class PastClose(
        val end: ConnEnd,
    ) : SendRefusal

    /** The connection ended with [end]. */
    class Ended(
        val end: ConnEnd,
    ) : SendRefusal
}

/** What an operation that would hand quiche stream data may use: the open table's sender, or why it is refused. */
internal fun StreamTable.sender(): Sender =
    when (val now = state) {
        is Streams.Open -> now
        is Streams.Closing -> Sender.Refused(SendRefusal.PastClose(now.end))
        is Streams.Ended -> Sender.Refused(SendRefusal.Ended(now.end))
    }

/** What [StreamTable.tryOpen] did. */
internal enum class Opened { Opened, AtLimit }
