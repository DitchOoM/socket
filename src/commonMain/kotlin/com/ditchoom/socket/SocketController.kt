package com.ditchoom.socket

import com.ditchoom.buffer.flow.ByteStream

/**
 * A bidirectional socket as a [ByteStream] (read + write + the injected read/write policies +
 * [close]), plus socket-level addressing.
 *
 * Replaces the old `SocketController : Reader, Writer` shape — `isOpen`, `read`, `write`,
 * `writeGathered`, and `close` now come from the byte trichotomy in `buffer-flow`.
 */
interface SocketController : ByteStream {
    suspend fun localPort(): Int

    suspend fun remotePort(): Int
}
