package com.ditchoom.socket

import com.ditchoom.websocket.NativeWebsocket
import com.ditchoom.websocket.WebSocket
import com.ditchoom.websocket.WebSocketConnectionOptions

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

