package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import kotlinx.coroutines.channels.Channel

/**
 * No-op [UdpChannel] for driver unit tests.
 * Does not perform real I/O — the driver is tested with [clientMode] = false,
 * so the UDP reader loop is never started.
 */
class StubUdpChannel : UdpChannel {
    override suspend fun receive(buffer: PlatformBuffer): Int {
        // Should never be called in tests (clientMode = false)
        Channel<Unit>().receive() // suspend forever
        return 0
    }

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
    ) {
        // No-op: tests don't need real UDP sends
    }

    override fun close() {}
}
