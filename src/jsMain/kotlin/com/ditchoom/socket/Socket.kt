package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone

val isNodeJs = nodeJs()


private fun nodeJs(): Boolean {
    return js("global.window") == null
}

fun ClientSocket.Companion.allocate(tls: Boolean): ClientToServerSocket = allocate(tls, AllocationZone.Direct)
actual fun ClientSocket.Companion.allocate(tls: Boolean, zone: AllocationZone): ClientToServerSocket {
    return if (isNodeJs) {
        NodeClientSocket(tls, zone)
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}

fun ServerSocket.Companion.allocate(tls: Boolean): ServerSocket = allocate(tls, AllocationZone.Direct)
actual fun ServerSocket.Companion.allocate(tls: Boolean, zone: AllocationZone): ServerSocket {
    if (isNodeJs) {
//        throw UnsupportedOperationException("Not implemented yet")
        return NodeServerSocket(tls, zone)
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}

external fun require(module: String): dynamic