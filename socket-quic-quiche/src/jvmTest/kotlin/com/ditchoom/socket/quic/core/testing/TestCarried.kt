package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.conn.Carried
import com.ditchoom.socket.quic.core.conn.CarriedCell
import com.ditchoom.socket.quic.core.path.FeedWake
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind

/** The network a test client's first socket opened on, and its moves ask for. */
internal val TEST_NETWORK: NetworkId = NetworkId.Link(NetworkKind.Wifi, 1L)

/** Where a test client's loop publishes which socket carries, read by no feed: the first socket, on [TEST_NETWORK]. */
internal fun unreadCarried(): CarriedCell =
    CarriedCell(FeedWake(), Carried(SocketTicket.FIRST, PathKey(family = 4, port = 0, hi = 0L, lo = 0L), TEST_NETWORK))
