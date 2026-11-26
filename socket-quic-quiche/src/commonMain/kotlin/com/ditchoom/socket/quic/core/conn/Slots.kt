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
    /** Every turn writes the ACK quiche owes. */
    data object Immediate : AckCadence

    /** A turn that handed received data up lets its ACK wait until the end of the pass ([SamePassHold]). */
    class UntilPassEnds(
        val hold: SamePassHold,
    ) : AckCadence

    /** A turn that handed received data up may hold its ACK across turns for the reply ([AckHold]). */
    class AcrossTurns(
        val hold: AckHold,
    ) : AckCadence
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
    /** A client: the session it offered, its first flight's gate, the ticket its server issues, and its 0-RTT's [early] bytes. */
    class Client(
        val session: ClientSession,
        val early: EarlyPlan,
    ) : Resumption

    /** A server: whether it accepted the client's 0-RTT, and how its handshake used the client's ticket. */
    class Server(
        val session: ServerSession,
    ) : Resumption
}

internal sealed interface Datagrams {
    data object Off : Datagrams

    /** RFC 9221 datagrams are carried: [box] holds what arrives, and [out] sends what is queued. */
    class On(
        val box: DatagramBox,
        val out: DatagramSender,
    ) : Datagrams
}

internal sealed interface KeepAlive {
    data object Off : KeepAlive

    /** An idle connection is pinged by [timer]. */
    class Every(
        val timer: KeepAliveTimer,
    ) : KeepAlive
}

/** What one connection holds: exactly one variant of each concern. */
internal class ConnSlots(
    val pacing: Pacing,
    val ack: AckCadence,
    val paths: Paths,
    val resumption: Resumption,
    val datagrams: Datagrams,
    val keepAlive: KeepAlive,
)
