package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineDispatcher

internal actual class ServerHandlerThreads actual constructor(
    serverName: String,
) {
    actual val dispatcher: CoroutineDispatcher = serverReceiveDispatcher

    actual fun release() = Unit
}
