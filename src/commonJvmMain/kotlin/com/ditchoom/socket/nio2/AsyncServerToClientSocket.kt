package com.ditchoom.socket.nio2

import com.ditchoom.buffer.PlatformBuffer
import java.nio.channels.AsynchronousSocketChannel

class AsyncServerToClientSocket(
    bufferFactory: () -> PlatformBuffer,
    asyncSocket: AsynchronousSocketChannel
) : AsyncBaseClientSocket(bufferFactory) {
    init {
        this.socket = asyncSocket
    }
}