@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: a client's path calls are made here.

package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.MigrateOutcome
import com.ditchoom.socket.quic.ProbeOutcome
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.udp.SockAddr

/** A client's quiche path calls toward its one peer, each named by the local endpoint it leaves from. */
internal class TowardPeer(
    private val q: QuicheConnRef,
    private val first: SinglePath,
) {
    /** Asks quiche to validate the path from [local]: a new path, or another PATH_CHALLENGE on one it holds. */
    fun probe(local: SockAddr): ProbeOutcome =
        q.api.connProbePath(q.handle, local.address, local.length, first.peer.address, first.peer.length)

    fun probe(local: LocalAddr): ProbeOutcome = probe(sockAddrOf(local))

    fun migrate(to: LocalAddr): MigrateOutcome {
        val local = sockAddrOf(to)
        return q.api.connMigrate(q.handle, local.address, local.length, first.peer.address, first.peer.length)
    }

    /** What the path from [left] had in flight is resent on the active path. */
    fun requeue(left: LocalAddr) {
        val local = sockAddrOf(left)
        q.api.connRequeuePath(q.handle, local.address, local.length, first.peer.address, first.peer.length)
    }

    fun recvInfoAt(local: EncodedSockAddr): QuicheRecvInfo =
        q.api.recvInfoNew(first.peer.address, first.peer.length, local.address, local.length)

    private fun sockAddrOf(local: LocalAddr): SockAddr =
        when (val made = local.made) {
            is Made.Here -> SockAddr(made.sockAddr.address, made.sockAddr.length)
            is Made.ByEdge -> first.local
        }
}
