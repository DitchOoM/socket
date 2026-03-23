package com.ditchoom.socket

val isNodeJs = nodeJs()

private fun nodeJs(): Boolean = js("global.window") == null

actual fun ClientSocket.Companion.allocate(): ClientToServerSocket =
    if (js("global.window") == null) {
        NodeClientSocket()
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }

actual fun ServerSocket.Companion.allocate(): ServerSocket {
    if (js("global.window") == null) {
//        throw UnsupportedOperationException("Not implemented yet")
        return NodeServerSocket()
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}
