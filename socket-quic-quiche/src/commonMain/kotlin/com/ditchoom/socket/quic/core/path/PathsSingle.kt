@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: every Paths variant lives in one.

package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.life.RttSample
import com.ditchoom.socket.quic.core.udp.SockAddr
import kotlin.time.Duration

/**
 * The path a connection was opened on: what quiche is told about each datagram that arrives on it, and where each
 * one it writes goes. Both sockaddrs must outlive the infos, which keep pointers to them.
 */
internal class SinglePath(
    private val q: QuicheConnRef,
    val peer: SockAddr,
    val local: SockAddr,
    val target: SendTarget,
) {
    val recvInfo: QuicheRecvInfo = q.api.recvInfoNew(peer.address, peer.length, local.address, local.length)
    val sendInfo: QuicheSendInfo = q.api.sendInfoNew()

    /** What quiche's statistics for the path say now. Allocates: read only on a handshake's rare events. */
    fun read(): PathRead {
        val stats = q.api.connPathStats(q.handle, 0L) ?: return PathRead.Unread
        val sample = if (stats.minRtt == Duration.ZERO) RttSample.None else RttSample.Sampled(stats.rtt)
        return PathRead.Read(stats.recv, stats.totalPtoCount, sample)
    }
}

/** A reading of the path's statistics. */
internal sealed interface PathRead {
    /** quiche gave no statistics. */
    data object Unread : PathRead

    class Read(
        /** Packets received on the path. */
        val received: Long,
        /** PTO expiries on the path. */
        val probes: Long,
        val rtt: RttSample,
    ) : PathRead
}
