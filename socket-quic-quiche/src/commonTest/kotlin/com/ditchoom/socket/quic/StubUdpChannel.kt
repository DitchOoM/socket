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
    ): SendOutcome {
        sendCount++
        // [sendBehavior] keeps its throwing shape so existing tests read unchanged; the conversion to
        // the reporting contract goes through the same [sendOutcomeOf] the real backends use, so a
        // test double cannot classify a failure differently from production.
        return sendOutcomeOf { sendBehavior(buffer, len) }
    }

    /**
     * How many times the driver closed this channel. A migration path that is abandoned must have its
     * socket closed at that moment — not left to `cleanup()` when the whole connection dies — so a
     * teardown test needs to see the close happen while the connection is still live.
     */
    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount++
    }
}
