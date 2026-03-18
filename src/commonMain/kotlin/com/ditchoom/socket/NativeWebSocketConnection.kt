package com.ditchoom.socket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.SuspendCloseable

/**
 * Platform-native WebSocket connection interface.
 *
 * On Apple platforms, implemented using NWConnection with NWProtocolWebSocket.
 * Use [connectNativeWebSocket] (available in Apple source sets) to create an instance.
 */
interface NativeWebSocketConnection : SuspendCloseable {
    val isOpen: Boolean

    suspend fun receiveMessage(): NativeWebSocketMessage

    suspend fun sendMessage(
        data: ReadBuffer?,
        opcode: Int,
        closeCode: Int = 0,
    )

    companion object {
        const val OPCODE_TEXT = 1
        const val OPCODE_BINARY = 2
        const val OPCODE_CLOSE = 8
        const val OPCODE_PING = 9
        const val OPCODE_PONG = 10
    }
}

/**
 * Create a [NativeWebSocketConnection] and connect to the given WebSocket URL.
 *
 * Only implemented on Apple platforms (uses NWConnection with WebSocket protocol).
 * Non-Apple actuals throw [UnsupportedOperationException] — the websocket module's
 * factory silently falls back to [DefaultWebSocketClient] on those platforms.
 *
 * expect/actual is required here because appleMain metadata compilation cannot resolve
 * Apple-specific types from dependencies, so the implementation lives in per-target
 * source sets via the appleNativeImpl srcDir hack.
 */
expect suspend fun connectNativeWebSocket(
    url: String,
    tls: Boolean = true,
    verifyCertificates: Boolean = true,
    autoReplyPing: Boolean = true,
    subprotocols: List<String>? = null,
    timeoutSeconds: Int = 30,
): NativeWebSocketConnection

data class NativeWebSocketMessage(
    val data: ReadBuffer?,
    val opcode: Int, // 1=text, 2=binary, 8=close, 9=ping, 10=pong
    val closeCode: Int = 0,
)
