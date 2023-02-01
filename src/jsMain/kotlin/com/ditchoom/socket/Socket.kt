package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import kotlinx.coroutines.CoroutineScope

val isNodeJs = nodeJs()

private fun nodeJs(): Boolean {
    return js("global.window") == null
}

actual fun ClientSocket.Companion.allocate(
    tls: Boolean,
    bufferFactory: () -> PlatformBuffer
): ClientToServerSocket {
    return if (js("global.window") == null) {
        val s = NodeClientSocket(tls, bufferFactory)
        println("isnodejs = true")
        s
    } else {
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}

actual fun ServerSocket.Companion.allocate(
    scope: CoroutineScope,
    bufferFactory: () -> PlatformBuffer
): ServerSocket {
    if (js("global.window") == null) {
//        throw UnsupportedOperationException("Not implemented yet")
        return NodeServerSocket(scope)
    } else {
        println("server isnodejs = true")
        throw UnsupportedOperationException("Sockets are not supported in the browser")
    }
}
