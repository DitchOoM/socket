package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer

/**
 * Platform-specific UDP datagram channel.
 *
 * Abstracts the platform I/O mechanism (NIO Selector on JVM, io_uring on Linux)
 * so the [QuicheDriver] event loop is platform-neutral.
 */
interface UdpChannel {
    /** Receive one UDP datagram into [buffer]. Suspends until data arrives. Returns bytes received. */
    suspend fun receive(buffer: PlatformBuffer): Int

    /**
     * Send [len] bytes from [buffer]. When [dest] is null, send to the channel's connected/fixed
     * peer (the common case). When non-null — set by the server egress path from quiche's
     * `sendInfo.to` — send to that address instead, so replies follow a migrated peer to its new
     * source address. Channels that cannot target an arbitrary destination ignore [dest].
     */
    suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        dest: PathKey? = null,
    )

    /** Close the underlying socket. */
    fun close()
}
