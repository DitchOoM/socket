package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.demux.ServerPhase
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life

/** A place in a loop's socket table: an endpoint, or empty. */
internal sealed interface LoopSlot {
    data object Empty : LoopSlot

    class Taken(
        val endpoint: Endpoint,
    ) : LoopSlot
}

/** Where an endpoint's datagrams go. */
internal sealed interface Route {
    /** A client's own connected socket carries exactly one connection, whose output collects in [out]. */
    class Fixed(
        val conn: Conn,
        val out: Outbox,
    ) : Route

    /**
     * A server's unconnected socket carries every connection it accepted. Its receive phase answers datagrams into [out],
     * so the batch is the socket's own.
     */
    class Demux(
        val demux: com.ditchoom.socket.quic.core.demux.Demux,
        val out: OwnBatch,
    ) : Route
}

/** Whether the connection, or the server, this route carries has ended. */
internal fun Route.ended(): Boolean =
    when (this) {
        is Route.Fixed -> conn.life() is Life.Ended
        is Route.Demux -> demux.phase() == ServerPhase.Stopped
    }

/** Ends every connection this route carries with [end]. */
internal fun Route.endWith(end: ConnEnd) =
    when (this) {
        is Route.Fixed -> conn.endWithLoop(end)
        is Route.Demux -> demux.endAll(end)
    }

/** Whether an endpoint is still on its loop after a serve. */
internal enum class Stay { Stays, Left }
