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
    ) : Live

    /** Past the close point of a close that ends with [end]: a write or an open is refused with it; reads go on. */
    class Closing(
        val end: ConnEnd,
    ) : Live

    class Ended(
        val end: ConnEnd,
    ) : Streams
}

/** What [StreamTable.tryOpen] did. */
internal enum class Opened { Opened, AtLimit }
