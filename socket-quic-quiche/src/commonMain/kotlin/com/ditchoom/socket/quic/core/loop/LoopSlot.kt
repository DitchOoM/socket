package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.conn.Conn

/** A place in a loop's socket table: an endpoint, or empty. */
internal sealed interface LoopSlot {
    data object Empty : LoopSlot

    class Taken(
        val endpoint: Endpoint,
    ) : LoopSlot
}

/** Where an endpoint's datagrams go. */
internal sealed interface Route {
    /** A client's own connected socket carries exactly one connection. */
    class Fixed(
        val conn: Conn,
    ) : Route

    /** A server's unconnected socket carries every connection it accepted. */
    class Demux(
        val demux: com.ditchoom.socket.quic.core.demux.Demux,
    ) : Route
}

/** Whether an endpoint is still on its loop after a serve. */
internal enum class Stay { Stays, Left }
