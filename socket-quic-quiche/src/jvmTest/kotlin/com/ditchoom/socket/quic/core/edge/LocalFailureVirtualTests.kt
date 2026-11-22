package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A connection the core ends for a failure of its own tells its peer, on a real core client and server over a 60 ms
 * round trip, on virtual time: the peer ends by the close, with INTERNAL_ERROR, within a round trip, not at its idle
 * timeout.
 */
class LocalFailureVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun workThatThrowsInsideAConnectionEndsItAndThePeerIsToldWithInternalError() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val asked = pair.now()
            pair.client.parts.conn
                .submit(Throws(), CallerPlace.HandToLoop)
            pair.runUntil { serverSide.end() is EdgeEnd.Ended }
            assertIs<ConnEnd.ProcessingFailed>(assertIs<EdgeEnd.Ended>(pair.client.connection.end()).end)
            val end = assertIs<ConnEnd.ByPeer>(assertIs<EdgeEnd.Ended>(serverSide.end()).end)
            assertEquals(INTERNAL_ERROR, end.error.code, "INTERNAL_ERROR, nothing more")
            assertTrue(pair.now() - asked < 1.seconds.inWholeNanoseconds, "told within a round trip: ${(pair.now() - asked) / MS} ms")
        }

    /** Work that throws inside the connection. */
    private class Throws : ConnWork() {
        override fun run(inside: Inside): Unit = throw IllegalStateException()

        override fun gone(end: ConnEnd) = Unit
    }

    private companion object {
        const val MS = 1_000_000L
        const val INTERNAL_ERROR = 0x1L
    }
}
