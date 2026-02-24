package com.ditchoom.socket

actual suspend fun connectNativeWebSocket(
    url: String,
    tls: Boolean,
    verifyCertificates: Boolean,
    autoReplyPing: Boolean,
    subprotocols: List<String>?,
    timeoutSeconds: Int,
): NativeWebSocketConnection {
    throw UnsupportedOperationException("NativeWebSocketConnection is only supported on Apple platforms")
}
