package com.ditchoom.socket

import com.ditchoom.websocket.WebSocketConnectionOptions
import kotlinx.coroutines.CoroutineScope
import kotlin.time.ExperimentalTime

enum class NetworkCapabilities {
    FULL_SOCKET_ACCESS,
    WEBSOCKETS_ONLY
}


expect fun getNetworkCapabilities(): NetworkCapabilities


@ExperimentalTime
expect suspend fun getWebSocketClient(
    scope: CoroutineScope,
    connectionOptions: WebSocketConnectionOptions,
): SocketController