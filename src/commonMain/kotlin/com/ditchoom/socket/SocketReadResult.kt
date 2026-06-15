package com.ditchoom.socket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded

/**
 * Translates a platform read that returns a [ReadBuffer] (and signals end/reset by throwing
 * [SocketClosedException]) into the [ReadResult] trichotomy.
 *
 * This is the logic the deleted `TcpByteStream` adapter used to perform. Now that a `ClientSocket`
 * **is** a [com.ditchoom.buffer.flow.ByteStream] (no adapter), each platform socket's
 * `read(deadline): ReadResult` wraps its existing throwing read body with this helper:
 *
 * ```kotlin
 * override suspend fun read(deadline: Duration): ReadResult = translateRead { readRaw(deadline) }
 * ```
 *
 * - a non-empty buffer  → [ReadResult.Data]
 * - an empty buffer / clean EOF / [SocketClosedException] → [ReadResult.End]
 * - [SocketClosedException.ConnectionReset] → [ReadResult.Reset]
 */
internal suspend inline fun translateRead(read: () -> ReadBuffer): ReadResult =
    try {
        val buffer = read()
        if (buffer.remaining() <= 0) {
            buffer.freeIfNeeded()
            ReadResult.End
        } else {
            ReadResult.Data(buffer)
        }
    } catch (_: SocketClosedException.ConnectionReset) {
        ReadResult.Reset
    } catch (_: SocketClosedException) {
        ReadResult.End
    }
