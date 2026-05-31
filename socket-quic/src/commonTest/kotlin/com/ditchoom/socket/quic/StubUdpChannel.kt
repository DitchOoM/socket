package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import kotlinx.coroutines.channels.Channel

/**
 * No-op [UdpChannel] for driver unit tests.
 * Does not perform real I/O — the driver is tested with [clientMode] = false,
 * so the UDP reader loop is never started.
 *
 * Tests that need to inject UDP send errors (e.g. PortUnreachableException,
 * ClosedChannelException) supply [sendBehavior] which is invoked on each send.
 */
class StubUdpChannel(
    private val sendBehavior: (PlatformBuffer, Int) -> Unit = { _, _ -> },
) : UdpChannel {
    var sendCount: Int = 0
        private set

    override suspend fun receive(buffer: PlatformBuffer): Int {
        // Should never be called in tests (clientMode = false)
        Channel<Unit>().receive() // suspend forever
        return 0
    }

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        dest: PathKey?,
    ) {
        sendCount++
        sendBehavior(buffer, len)
    }

    override fun close() {}
}
