package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone

val isNodeJs = nodeJs()


private fun nodeJs(): Boolean {
    return js("global.window") == null
}

fun asyncClientSocket(): ClientToServerSocket {
    return asyncClientSocket(AllocationZone.Direct)
}

actual fun asyncClientSocket(zone: AllocationZone): ClientToServerSocket {
    return if (isNodeJs) {
        NodeClientSocket()
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}

fun clientSocket(blocking: Boolean): ClientToServerSocket = clientSocket(AllocationZone.Direct, blocking)

actual fun clientSocket(zone: AllocationZone, blocking: Boolean): ClientToServerSocket =
    throw UnsupportedOperationException("Only non blocking io is supported with JS")

fun asyncServerSocket(): ServerSocket = asyncServerSocket(AllocationZone.Direct)
actual fun asyncServerSocket(zone: AllocationZone): ServerSocket {
    if (isNodeJs) {
//        throw UnsupportedOperationException("Not implemented yet")
        return NodeServerSocket(zone)
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}

external fun require(module: String): dynamic