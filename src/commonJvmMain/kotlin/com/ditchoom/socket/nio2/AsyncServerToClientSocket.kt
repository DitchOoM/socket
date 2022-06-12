package com.ditchoom.socket.nio2

import java.nio.channels.AsynchronousSocketChannel

class AsyncServerToClientSocket(asyncSocket: AsynchronousSocketChannel) : AsyncBaseClientSocket() {
    init {
        this.socket = asyncSocket
    }
}