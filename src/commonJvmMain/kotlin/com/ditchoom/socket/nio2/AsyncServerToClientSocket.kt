package com.ditchoom.socket.nio2

import java.nio.channels.AsynchronousSocketChannel
import kotlin.time.ExperimentalTime

class AsyncServerToClientSocket(asyncSocket: AsynchronousSocketChannel) : AsyncBaseClientSocket() {
    init {
        this.socket = asyncSocket
    }
}