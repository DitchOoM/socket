package com.ditchoom.socket

val isNodeJs = nodeJs()


private fun nodeJs(): Boolean {
    return js("global.window") == null
}

actual fun asyncClientSocket(): ClientToServerSocket {
    return if (isNodeJs) {
        NodeClientSocket()
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}


actual fun clientSocket(blocking: Boolean): ClientToServerSocket =
    throw UnsupportedOperationException("Only non blocking io is supported with JS")

actual fun asyncServerSocket(): ServerSocket {
    if (isNodeJs) {
//        throw UnsupportedOperationException("Not implemented yet")
        return NodeServerSocket()
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}

external fun require(module: String): dynamic