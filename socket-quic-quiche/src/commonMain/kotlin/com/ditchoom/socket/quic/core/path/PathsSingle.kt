package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.life.QuicheConnRef

/** A socket address in native memory, as quiche reads it. */
internal class SockAddr(
    val address: Long,
    val length: Int,
)

/**
 * The one path a connection has until migration arrives: what quiche is told about each datagram that arrives, and
 * where each one it writes goes. Both sockaddrs must outlive the infos, which keep pointers to them.
 */
internal class SinglePath(
    q: QuicheConnRef,
    peer: SockAddr,
    local: SockAddr,
    val target: SendTarget,
) {
    val recvInfo: QuicheRecvInfo = q.api.recvInfoNew(peer.address, peer.length, local.address, local.length)
    val sendInfo: QuicheSendInfo = q.api.sendInfoNew()
}
