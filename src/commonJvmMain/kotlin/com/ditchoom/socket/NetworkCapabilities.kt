package com.ditchoom.socket

import com.ditchoom.websocket.NativeWebsocket
import com.ditchoom.websocket.WebSocket
import com.ditchoom.websocket.WebSocketConnectionOptions

actual suspend fun getWebSocketClient(
    connectionOptions: WebSocketConnectionOptions,
): WebSocket = NativeWebsocket.open(connectionOptions)

actual fun getNetworkCapabilities() = NetworkCapabilities.FULL_SOCKET_ACCESS