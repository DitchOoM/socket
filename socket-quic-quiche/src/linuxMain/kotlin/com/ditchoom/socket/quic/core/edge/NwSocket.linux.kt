package com.ditchoom.socket.quic.core.edge

/** Linux has no Network.framework: no value of this type exists. */
internal actual class NwSocket private constructor(
    private val impossible: Nothing,
) {
    fun never(): Nothing = impossible
}

internal actual fun closeNwSocket(socket: NwSocket): Unit = socket.never()

internal actual fun nwSocketIo(
    socket: StackSocket.Network,
    wake: () -> Unit,
): SocketIo = socket.socket.never()
