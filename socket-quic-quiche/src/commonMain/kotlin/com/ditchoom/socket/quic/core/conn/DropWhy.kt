package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.testkit.trace.TraceEvent

/** Why a connection dropped a datagram, as its flight recording names it: a trace line `DROP <type> <detail>`. */
internal enum class DropWhy {
    /** Quiche wrote it for a path no socket of ours holds: a client's own address that is gone, or a server's unknown peer. */
    NoSocketForPath,

    /** It came from a peer address beyond the most a server connection follows. */
    TooManyPeerAddresses,
    ;

    /** The trace's [TraceEvent.Drop.type]: this reason's qualified name. */
    val type: String get() = DROP_TYPE_PREFIX + name
}

private const val DROP_TYPE_PREFIX = "com.ditchoom.socket.quic.core.conn.DropWhy."
