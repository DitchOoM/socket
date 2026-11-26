package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.SendsRefusedWork
import com.ditchoom.socket.quic.core.demux.ServerPhase
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.observe.TraceSection
import com.ditchoom.socket.quic.core.observe.traced
import com.ditchoom.socket.quic.core.sched.RefusalNews
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.SocketTicket

/** A place in a loop's socket table: an endpoint, or empty. */
internal sealed interface LoopSlot {
    data object Empty : LoopSlot

    /** [endpoint]'s carrying socket: the slot that serves it. */
    class Taken(
        val endpoint: Endpoint,
    ) : LoopSlot

    /** [endpoint]'s second socket, the one its [Via.Probing] names: read here, served from its endpoint's own slot. */
    class Second(
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

/** Where the route's output collects. */
internal fun Route.outbox(): Outbox =
    when (this) {
        is Route.Fixed -> out
        is Route.Demux -> out
    }

/** Ends every connection this route carries with [end]. */
internal fun Route.endWith(end: ConnEnd) =
    when (this) {
        is Route.Fixed -> conn.endWithLoop(end)
        is Route.Demux -> demux.endAll(end)
    }

/**
 * Loop thread, at each look at a live socket: the news that the network refused the first datagram it was handed, by
 * the loop's flush, a lane it flushed or a caller's own send, goes to a client's connection to judge.
 */
internal fun Route.refused(news: RefusalNews) =
    when (news) {
        RefusalNews.None -> Unit
        RefusalNews.Due ->
            when (this) {
                is Route.Fixed -> conn.submitFromLoop(SendsRefusedWork())
                is Route.Demux -> Unit
            }
    }

/** The datagrams of one read on the socket [on] names, in a receive phase that began [at]. */
internal fun Route.deliver(
    batch: ReceiveBatch,
    at: Nanos,
    on: SocketTicket,
) {
    when (this) {
        is Route.Fixed -> for (i in 0 until batch.count) {
            conn.received(batch.bufferAt(i).bytes, batch.lengthAt(i), batch.fromAt(i), at, on)
        }
        is Route.Demux -> traced(TraceSection.Demux) { demux.route(batch, out.batch, at) }
    }
    batch.release()
}

/** Whether an endpoint is still on its loop after a serve. */
internal enum class Stay { Stays, Left }
