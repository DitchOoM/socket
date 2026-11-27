@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: a client's path calls are made here.

package com.ditchoom.socket.quic.core.path

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.MigrateOutcome
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.ProbeOutcome
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.SockAddr
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.decodePathKey
import com.ditchoom.socket.quic.encodeToNative

/** A client's quiche path calls toward its one peer, each named by the local endpoint it leaves from. */
internal class TowardPeer(
    private val q: QuicheConnRef,
    private val first: SinglePath,
) {
    /**
     * Asks quiche to validate the path from [local]: a new path, or another PATH_CHALLENGE on one it holds. The path
     * assumes [prior] until it has an RTT sample, and its challenge leaves past the congestion window.
     */
    fun probe(
        local: SockAddr,
        prior: RttPrior,
    ): ProbeOutcome = q.api.connProbePathWith(q.handle, local.address, local.length, first.peer.address, first.peer.length, prior.nanos)

    fun probe(local: LocalAddr): ProbeOutcome = probe(sockAddrOf(local), local.prior)

    fun migrate(to: LocalAddr): MigrateOutcome {
        val local = sockAddrOf(to)
        return q.api.connMigrate(q.handle, local.address, local.length, first.peer.address, first.peer.length)
    }

    /** What the path from [left] had in flight is resent on the active path. */
    fun requeue(left: LocalAddr) {
        val local = sockAddrOf(left)
        q.api.connRequeuePath(q.handle, local.address, local.length, first.peer.address, first.peer.length)
    }

    /** The endpoint of the loop's socket [ticket], at [local], toward the peer over a network assumed [prior]. */
    fun endpointAt(
        local: PathKey,
        prior: RttPrior,
        ticket: SocketTicket,
        factory: BufferFactory,
    ): LocalAddr {
        val sockAddr = local.encodeToNative(factory)
        return LocalAddr(q.api.decodePathKey(sockAddr.address), recvInfoAt(sockAddr), Made.Here(sockAddr), prior, ticket, first.target)
    }

    private fun recvInfoAt(local: EncodedSockAddr): QuicheRecvInfo =
        q.api.recvInfoNew(first.peer.address, first.peer.length, local.address, local.length)

    private fun sockAddrOf(local: LocalAddr): SockAddr =
        when (val made = local.made) {
            is Made.Here -> SockAddr(made.sockAddr.address, made.sockAddr.length)
            is Made.ByEdge -> first.local
        }
}
