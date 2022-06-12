package com.ditchoom.socket

import com.ditchoom.websocket.WebSocket
import com.ditchoom.websocket.WebSocketConnectionOptions

enum class NetworkCapabilities {
    FULL_SOCKET_ACCESS,
    WEBSOCKETS_ONLY
}


expect fun getNetworkCapabilities(): NetworkCapabilities


expect suspend fun getWebSocketClient(
    connectionOptions: WebSocketConnectionOptions,
): WebSocket