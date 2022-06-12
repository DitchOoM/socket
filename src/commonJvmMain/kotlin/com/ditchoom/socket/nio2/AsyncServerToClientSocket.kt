package com.ditchoom.socket.nio2

import com.ditchoom.buffer.AllocationZone
import java.nio.channels.AsynchronousSocketChannel

class AsyncServerToClientSocket(
    override val allocationZone: AllocationZone = AllocationZone.Direct,
    asyncSocket: AsynchronousSocketChannel
) : AsyncBaseClientSocket() {
    init {
        this.socket = asyncSocket
    }
}