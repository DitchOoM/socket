package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.path.ClientPaths
import com.ditchoom.socket.quic.core.path.ServerPaths

// One sealed type per concern. No members: decision sites are `when` in Turn.

internal sealed interface Pacing {
    data object Unpaced : Pacing

    class ByQuiche(
        val pacer: Pacer,
    ) : Pacing
}

internal sealed interface AckCadence {
    data object Immediate : AckCadence
}

internal sealed interface Paths {
    /** A client's socket moves when its local address does, and the connection migrates with it. */
    class Client(
        val paths: ClientPaths,
    ) : Paths

    /** A server follows a peer that moves. */
    class Server(
        val paths: ServerPaths,
    ) : Paths
}

internal sealed interface Resumption {
    data object None : Resumption
}

internal sealed interface Datagrams {
    data object Off : Datagrams
}

/** What one connection holds: exactly one variant of each concern. */
internal class ConnSlots(
    val pacing: Pacing,
    val ack: AckCadence,
    val paths: Paths,
    val resumption: Resumption,
    val datagrams: Datagrams,
)
