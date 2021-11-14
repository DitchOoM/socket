package com.ditchoom.socket

import com.ditchoom.websocket.WebSocket
import com.ditchoom.websocket.WebSocketConnectionOptions
import kotlin.time.ExperimentalTime

enum class NetworkCapabilities {
    FULL_SOCKET_ACCESS,
    WEBSOCKETS_ONLY
}


expect fun getNetworkCapabilities(): NetworkCapabilities


@ExperimentalUnsignedTypes
@ExperimentalTime
expect suspend fun getWebSocketClient(
    connectionOptions: WebSocketConnectionOptions,
): WebSocket