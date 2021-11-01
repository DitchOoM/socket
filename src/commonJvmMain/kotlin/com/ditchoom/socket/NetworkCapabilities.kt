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
): SocketController = ClientWebSocket.open(scope, connectionOptions)

actual fun getNetworkCapabilities() = NetworkCapabilities.FULL_SOCKET_ACCESS