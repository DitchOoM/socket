package com.ditchoom.socket.quic

/**
 * The path quiche is sending on now, found in quiche's own path table — the only path whose RTT a timer
 * or a liveness verdict may be read from.
 *
 * quiche appends each new path to its table and keeps the ones a connection has left, their RTT
 * estimates frozen where sampling stopped. Index 0 is the original path, active only until the first
 * migration; after that its estimate is usually the worst one the connection ever held, because the
 * link was failing when the connection left it.
 */
internal sealed interface ActivePath {
    /** The active path is at [index] in quiche's table, and [stats] is one read of it. */
    class Read(
        val index: Long,
        val stats: QuicPathStats,
    ) : ActivePath

    /**
     * No path reads as active: the backend has not bound the path-stats FFI, or quiche is between
     * paths mid-switch. Either way there is no RTT sample to use.
     */
    data object Unreadable : ActivePath
}

/** Search [conn]'s path table for the active path. On the connection's driver loop only. */
internal fun QuicheApi.readActivePath(conn: QuicheConn): ActivePath {
    val count = connStats(conn)?.pathsCount ?: return ActivePath.Unreadable
    for (index in 0 until count) {
        val stats = connPathStats(conn, index) ?: continue
        if (stats.active) return ActivePath.Read(index, stats)
    }
    return ActivePath.Unreadable
}
