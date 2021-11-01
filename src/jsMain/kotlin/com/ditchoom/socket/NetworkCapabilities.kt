@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.websocket.ClientWebSocket
import com.ditchoom.websocket.WebSocketConnectionOptions
import kotlinx.coroutines.CoroutineScope
import kotlin.time.ExperimentalTime

@ExperimentalTime
actual suspend fun getWebSocketClient(
    scope: CoroutineScope,
    connectionOptions: WebSocketConnectionOptions,
): SocketController {
    return if (isNodeJs) {
        ClientWebSocket.open(scope, connectionOptions)
    } else {
        BrowserWebsocketController.open(scope, connectionOptions)
    }
}

actual fun getNetworkCapabilities() = if (isNodeJs) {
    NetworkCapabilities.FULL_SOCKET_ACCESS
} else {
    NetworkCapabilities.WEBSOCKETS_ONLY
}

