package com.ditchoom.socket.nio2

import com.ditchoom.buffer.AllocationZone
import java.nio.channels.AsynchronousSocketChannel

class AsyncServerToClientSocket(
    useTLS: Boolean,
    override val allocationZone: AllocationZone = AllocationZone.Direct,
    asyncSocket: AsynchronousSocketChannel
) : AsyncBaseClientSocket(useTLS) {
    init {
        this.socket = asyncSocket
    }

    override val isClient: Boolean = false

}