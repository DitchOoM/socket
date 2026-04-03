package com.ditchoom.socket.transport

import kotlinx.coroutines.flow.Flow

/**
 * A typed, bidirectional message connection.
 *
 * This is the composability interface between transport (socket) and protocol logic
 * (MQTT, WebSocket, etc.). Protocol libraries code against this interface — they don't
 * need to know whether the underlying transport is TCP, WebSocket, QUIC, or in-memory.
 *
 * Implementations:
 * - [CodecConnection]: framing via [com.ditchoom.buffer.codec.Codec] + [com.ditchoom.buffer.stream.StreamProcessor]
 * - `ReconnectingConnection`: reconnection skeleton wrapping a [CodecConnection] factory
 */
interface MessageConnection<T> {
    fun receive(): Flow<T>

    suspend fun send(message: T)

    suspend fun close()
}
