package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.core.conn.IssueIdsWork
import com.ditchoom.socket.quic.core.life.SpareId
import com.ditchoom.socket.quic.core.observe.LoopOnly

/**
 * The routes of the spare connection ids a server's connections hand their peers for new paths: routed before they are
 * handed, and unrouted once the peer retires them, so a connection whose peer moves often holds a handful.
 */
@LoopOnly
internal class SpareRoutes(
    private val table: RouteTable<ServerPeer>,
    private val admission: Admission,
) {
    private val cid = CidRead()

    /**
     * The ids [peer] may hand its own peer are routed here first, then handed to quiche inside the connection, so a
     * datagram that carries one always finds its connection.
     */
    fun issue(peer: ServerPeer) {
        val wanted = peer.conn.idsWanted()
        if (wanted == 0) return
        val spare = ArrayList<SpareId>(wanted)
        repeat(wanted) {
            val id = admission.spareId()
            cid.set(id.c0, id.c1, 0L, QUIC_ISSUED_CONN_ID_LEN)
            peer.routedSpare(table.add(cid, peer))
            spare.add(id)
        }
        peer.conn.submitFromLoop(IssueIdsWork(spare))
    }

    /** Stops routing the ids [peer]'s peer retired. */
    fun retire(peer: ServerPeer) {
        for (id in peer.conn.idsRetired()) {
            cid.set(id.c0, id.c1, 0L, QUIC_ISSUED_CONN_ID_LEN)
            val route = table.find(cid)
            if (route is Route<ServerPeer> && peer.unroutedSpare(route)) table.remove(route)
        }
    }
}
