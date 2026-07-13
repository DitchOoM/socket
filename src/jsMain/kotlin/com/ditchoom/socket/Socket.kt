package com.ditchoom.socket

val isNodeJs = nodeJs()

private fun nodeJs(): Boolean = js("global.window") == null

actual fun ClientSocket.Companion.allocate(config: TransportConfig): ClientToServerSocket =
    if (js("global.window") == null) {
        NodeClientSocket(config)
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }

actual fun ServerSocket.Companion.allocate(config: TransportConfig): ServerSocket {
    if (js("global.window") == null) {
//        throw UnsupportedOperationException("Not implemented yet")
        return NodeServerSocket(config)
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}
