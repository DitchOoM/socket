package com.ditchoom.socket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded

/**
 * Translates a platform read that returns a [ReadBuffer] (and signals end/reset by throwing
 * [SocketClosedException]) into the [ReadResult] trichotomy.
 *
 * A `ClientSocket` **is** a [com.ditchoom.buffer.flow.ByteStream] (no adapter), so each platform
 * socket's `read(deadline): ReadResult` wraps its throwing read body with this helper:
 *
 * ```kotlin
 * override suspend fun read(deadline: Duration): ReadResult = translateRead { readRaw(deadline) }
 * ```
 *
 * - a non-empty buffer  → [ReadResult.Data]
 * - an empty buffer / [SocketClosedException.EndOfStream] (the peer's graceful close) → [ReadResult.End]
 * - [SocketClosedException.ConnectionReset] → [ReadResult.Reset]
 * - any other [SocketClosedException] is thrown as it is.
 *
 * [ReadResult.End] promises the peer closed gracefully. A read cut short by this side's own close
 * ([SocketClosedException.General]), a broken pipe, or a transport's typed close are not that, and
 * reporting them as `End` let a caller take a failed connection for a finished exchange.
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
    } catch (_: SocketClosedException.EndOfStream) {
        ReadResult.End
    }
