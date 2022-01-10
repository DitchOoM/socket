@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.websocket.NativeWebsocket
import com.ditchoom.websocket.WebSocket
import com.ditchoom.websocket.WebSocketConnectionOptions
import kotlin.time.ExperimentalTime

@ExperimentalTime
actual suspend fun getWebSocketClient(
    connectionOptions: WebSocketConnectionOptions,
): WebSocket {
    return if (isNodeJs) {
        NativeWebsocket.open(connectionOptions)
    } else {
        BrowserWebsocketController.open(connectionOptions)
    }
}

actual fun getNetworkCapabilities() = if (isNodeJs) {
    NetworkCapabilities.FULL_SOCKET_ACCESS
} else {
    NetworkCapabilities.WEBSOCKETS_ONLY
}

