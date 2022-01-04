package com.ditchoom.socket

import kotlinx.browser.window
import org.w3c.dom.WebSocket
import org.w3c.dom.get
import kotlin.time.ExperimentalTime

val isNodeJs = nodeJs()


private fun nodeJs() :Boolean {
    return js("global.window") == null
}
@ExperimentalUnsignedTypes
@ExperimentalTime
actual fun asyncClientSocket(): ClientToServerSocket {
    return if (isNodeJs) {
        NodeClientSocket()
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}


@ExperimentalTime
actual fun clientSocket(blocking: Boolean): ClientToServerSocket =
    throw UnsupportedOperationException("Only non blocking io is supported with JS")

@ExperimentalUnsignedTypes
@ExperimentalTime
actual fun asyncServerSocket(): ServerSocket {
    if (isNodeJs) {
//        throw UnsupportedOperationException("Not implemented yet")
        return NodeServerSocket()
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}

external fun require(module: String): dynamic