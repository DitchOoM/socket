package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer

val isNodeJs = nodeJs()


private fun nodeJs(): Boolean {
    return js("global.window") == null
}

actual fun ClientSocket.Companion.allocate(bufferFactory: () -> PlatformBuffer): ClientToServerSocket {
    return if (isNodeJs) {
        NodeClientSocket(bufferFactory)
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}

actual fun ServerSocket.Companion.allocate(bufferFactory: () -> PlatformBuffer): ServerSocket {
    if (isNodeJs) {
//        throw UnsupportedOperationException("Not implemented yet")
        return NodeServerSocket()
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}

external fun require(module: String): dynamic