package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.core.life.ConnEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Keepalive on a real core client and server over a 60 ms round trip, on virtual time: an idle connection pings after
 * the interval and outlives its idle timeout, and with keepalive off it sends nothing and ends at the idle timeout.
 */
class CoreKeepAliveVirtualTests {
    private val idle = 2.seconds
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = idle, enablePmtuDiscovery = false)

    @Test
    fun anIdleConnectionWithKeepaliveOutlivesItsIdleTimeoutManyTimesOver() =
        VirtualPair.with(options.copy(keepAliveInterval = 500.milliseconds)) { pair ->
            val serverSide = pair.accept()
            val from = pair.now()
            pair.runFor(idle * 5)
            assertEquals(EdgeEnd.Open, pair.client.connection.end(), "the client is still open after five idle timeouts")
            assertEquals(EdgeEnd.Open, serverSide.end(), "so is the server")
            val pings = pair.sentBy(End.Client, from).size
            assertTrue(pings in MIN_PINGS..MAX_PINGS, "the client pinged about every 500 ms for ten seconds: $pings datagrams")
        }

    @Test
    fun aPingGoesOutOnlyAfterTheIntervalOfSilence() =
        VirtualPair.with(options.copy(keepAliveInterval = 800.milliseconds)) { pair ->
            pair.accept()
            val from = pair.now()
            pair.runFor(700.milliseconds)
            assertEquals(0, pair.sentBy(End.Client, from).size, "nothing is sent while the connection is still within the interval")
            pair.runFor(300.milliseconds)
            assertTrue(pair.sentBy(End.Client, from).isNotEmpty(), "a ping went out once the interval of silence passed")
        }

    @Test
    fun withoutKeepaliveAnIdleConnectionSendsNothingAndEndsAtItsIdleTimeout() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val from = pair.now()
            pair.runFor(1.seconds)
            assertEquals(0, pair.sentBy(End.Client, from).size, "an idle connection sends nothing without keepalive")
            pair.runUntil(within = idle * 3) { serverSide.end() is EdgeEnd.Ended && pair.client.connection.end() is EdgeEnd.Ended }
            assertIs<ConnEnd.IdleTimeout>(assertIs<EdgeEnd.Ended>(pair.client.connection.end()).end)
        }

    private companion object {
        const val MIN_PINGS = 10
        const val MAX_PINGS = 60
    }
}
