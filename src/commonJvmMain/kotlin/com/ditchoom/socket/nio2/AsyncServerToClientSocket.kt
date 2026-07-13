package com.ditchoom.socket.nio2

import com.ditchoom.socket.TransportConfig
import java.nio.channels.AsynchronousSocketChannel

class AsyncServerToClientSocket(
    asyncSocket: AsynchronousSocketChannel,
    config: TransportConfig = TransportConfig(),
) : AsyncBaseClientSocket(config) {
    init {
        this.socket = asyncSocket
    }
}
