package com.ditchoom.socket.quic.core.edge

internal actual val clientRideOfThisPlatform: ClientRide = ClientRide.OwnSocket

/** A JVM has no Network.framework: no value of this type exists. */
internal actual class NwConnector private constructor(
    private val impossible: Nothing,
) {
    fun never(): Nothing = impossible
}

internal actual suspend fun NwConnector.connect(
    host: String,
    port: Int,
): StackSocket.Network = never()
