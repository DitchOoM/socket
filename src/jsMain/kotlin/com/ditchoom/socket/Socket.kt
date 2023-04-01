package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone

val isNodeJs = nodeJs()

private fun nodeJs(): Boolean {
    return js("global.window") == null
}

actual fun ClientSocket.Companion.allocate(
    tls: Boolean,
    allocationZone: AllocationZone
): ClientToServerSocket {
    return if (js("global.window") == null) {
        NodeClientSocket(tls, allocationZone)
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}

actual fun ServerSocket.Companion.allocate(
    allocationZone: AllocationZone
): ServerSocket {
    if (js("global.window") == null) {
//        throw UnsupportedOperationException("Not implemented yet")
        return NodeServerSocket()
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}
