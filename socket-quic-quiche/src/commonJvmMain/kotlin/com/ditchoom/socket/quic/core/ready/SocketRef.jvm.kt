package com.ditchoom.socket.quic.core.ready

import java.nio.channels.SelectableChannel

internal actual class SocketRef(
    val channel: SelectableChannel,
)
