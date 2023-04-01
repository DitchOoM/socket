package com.ditchoom.socket.nio2

import com.ditchoom.buffer.AllocationZone
import java.nio.channels.AsynchronousSocketChannel

class AsyncServerToClientSocket(
    allocationZone: AllocationZone,
    asyncSocket: AsynchronousSocketChannel
) : AsyncBaseClientSocket(allocationZone) {
    init {
        this.socket = asyncSocket
    }
}
