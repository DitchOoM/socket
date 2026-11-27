package com.ditchoom.socket.quic.core.edge

/** What a client connection's datagrams ride: chosen once per platform, or lent by the caller. */
internal sealed interface ClientRide {
    /** A UDP socket of the loop's own stack. */
    data object OwnSocket : ClientRide

    /** A Network.framework connection, which follows the device's paths and brings up the radio it needs. */
    class NetworkFramework(
        val connector: NwConnector,
    ) : ClientRide

    /**
     * A channel the caller bound and keeps, shared with whatever else runs on its port: the connection never closes it,
     * never moves off it, and never follows the network itself.
     */
    class Lent(
        val channel: LentChannel,
    ) : ClientRide
}

/** Network.framework on Apple's Kotlin/Native targets; a socket of the loop's stack everywhere else. */
internal expect val clientRideOfThisPlatform: ClientRide

/** Opens Network.framework connections: made only on Apple's Kotlin/Native targets. */
internal expect class NwConnector

/** A ready connection to [host]:[port], or a typed failure raised on the caller's thread. */
internal expect suspend fun NwConnector.connect(
    host: String,
    port: Int,
): StackSocket.Network
