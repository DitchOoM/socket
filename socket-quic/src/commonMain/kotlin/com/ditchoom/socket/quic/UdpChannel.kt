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

    /** Send [len] bytes from [buffer] to the connected peer. */
    suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
    )

    /** Close the underlying socket. */
    fun close()
}
