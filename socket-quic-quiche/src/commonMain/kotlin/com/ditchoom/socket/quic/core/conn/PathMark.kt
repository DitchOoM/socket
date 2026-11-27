package com.ditchoom.socket.quic.core.conn

/** A change of a connection's path, as its flight recording names it. */
internal enum class PathMark {
    /** The client's loop opened a socket on a new local address and the path from it is probed. */
    Moved,

    /** The probed path was validated and the connection migrated to it. */
    Migrated,

    /** A move was refused, the connection staying on its socket: it could not follow, open, or keep the new one. */
    Refused,
    OpenRefused,

    /** A move was refused because the caller lent the socket that carries the connection. */
    LentRefused,

    /** A move was refused because the socket's io opens no second socket. */
    NoSecond,
    SecondLost,

    /** Quiche gave up validating the probed path or closed it: it is probed again later, or the move is given up. */
    ProbeFailed,
    Abandoned,

    /** A stranded endpoint was probed again, or tried to be. */
    Reprobed,

    /** The path being validated was asked for a fresh PATH_CHALLENGE. */
    Challenged,

    /** The client's socket was wanted on another address and waits where it is for a spare id from the peer. */
    Held,

    /** The active path stopped answering ([com.ditchoom.socket.quic.core.path.PathNews.Silent]), or answered again. */
    Silent,
    Answering,

    /** A read waited on a quiet path with nothing in flight: the client sent one PING (the awaiting-peer probe). */
    Pinged,

    /** A silent path asked for a fresh port, or answered first, was served, held or forbidden; or gave its move up. */
    Rebind,
    RebindStale,
    RebindServed,
    RebindHeld,
    RebindForbidden,
    RebindGaveUp,
}
