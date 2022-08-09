package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer

val isNodeJs = nodeJs()


private fun nodeJs(): Boolean {
    return js("global.window") == null
}

actual fun ClientSocket.Companion.allocate(
    tls: Boolean,
    bufferFactory: () -> PlatformBuffer
): ClientToServerSocket {
    return if (js("global.window") == null) {
        NodeClientSocket(tls, bufferFactory)
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}

actual fun ServerSocket.Companion.allocate(bufferFactory: () -> PlatformBuffer): ServerSocket {
    if (js("global.window") == null) {
//        throw UnsupportedOperationException("Not implemented yet")
        return NodeServerSocket()
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}

external fun require(module: String): dynamic