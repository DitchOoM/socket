package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.core.observe.LoopOnly

/** What an ended connection's first destination id routes to: nothing that starts a connection. */
internal data object EndedId

/**
 * The first destination ids of connections that ended lately. A client's Initial read after its connection ended (a
 * retransmit sent before the server answered, read late) is stale: it finds the id here and starts no connection. A
 * client picks a fresh random id for each connection it makes (RFC 9000 section 7.2), so no new one is refused. The
 * oldest is forgotten once [capacity] are kept.
 */
@LoopOnly
internal class EndedIds(
    private val capacity: Int,
) {
    private val table = RouteTable<EndedId>()
    private val order = ArrayList<Route<EndedId>>(0)
    private var oldest = 0

    /** [route] was a first destination id of a connection that ended now. */
    fun remember(route: Route<*>) {
        val kept = table.insert(route.retargeted(EndedId))
        if (order.size < capacity) {
            order.add(kept)
        } else {
            table.remove(order[oldest])
            order[oldest] = kept
            oldest = (oldest + 1) % capacity
        }
    }

    /** The ended connection's id [cid] names, or [RouteLink.End]. */
    fun find(cid: CidRead): RouteLink<EndedId> = table.find(cid)
}
