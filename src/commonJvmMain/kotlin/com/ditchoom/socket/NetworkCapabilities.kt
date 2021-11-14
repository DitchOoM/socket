@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.websocket.NativeWebsocket
import com.ditchoom.websocket.WebSocket
import com.ditchoom.websocket.WebSocketConnectionOptions
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalTime
actual suspend fun getWebSocketClient(
    connectionOptions: WebSocketConnectionOptions,
): WebSocket = NativeWebsocket.open(connectionOptions)

actual fun getNetworkCapabilities() = NetworkCapabilities.FULL_SOCKET_ACCESS