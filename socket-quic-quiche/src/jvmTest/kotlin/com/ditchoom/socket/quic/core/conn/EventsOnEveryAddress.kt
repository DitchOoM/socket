package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.MigrateOutcome
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuichePathEventType
import com.ditchoom.socket.quic.StubQuicheApi

/**
 * quiche's path events, each reported for whatever local address the path that is asked about holds: the stub
 * decodes no real sockaddr, so every endpoint of the rig reads as the same one. Counts the migrations taken.
 */
internal class EventsOnEveryAddress(
    private val inner: StubQuicheApi,
    val events: ArrayDeque<QuichePathEventType> = ArrayDeque(),
) : QuicheApi by inner {
    var migrations = 0
        private set

    override fun connPathEventNext(
        conn: QuicheConn,
        localOut: Long,
        localLenOut: Long,
        peerOut: Long,
        peerLenOut: Long,
    ): QuichePathEventType? = events.removeFirstOrNull()

    override fun connMigrate(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
    ): MigrateOutcome =
        inner.connMigrate(conn, localAddr, localLen, peerAddr, peerLen).also {
            if (it is MigrateOutcome.Migrated) migrations++
        }
}
