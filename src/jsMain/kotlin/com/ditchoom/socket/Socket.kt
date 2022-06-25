package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone

val isNodeJs = nodeJs()


private fun nodeJs(): Boolean {
    return js("global.window") == null
}

fun ClientSocket.Companion.allocate(): ClientToServerSocket = allocate(AllocationZone.Direct)
actual fun ClientSocket.Companion.allocate(zone: AllocationZone): ClientToServerSocket {
    return if (isNodeJs) {
        NodeClientSocket()
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}

fun ServerSocket.Companion.allocate(): ServerSocket = allocate(AllocationZone.Direct)
actual fun ServerSocket.Companion.allocate(zone: AllocationZone): ServerSocket {
    if (isNodeJs) {
//        throw UnsupportedOperationException("Not implemented yet")
        return NodeServerSocket(zone)
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}

external fun require(module: String): dynamic