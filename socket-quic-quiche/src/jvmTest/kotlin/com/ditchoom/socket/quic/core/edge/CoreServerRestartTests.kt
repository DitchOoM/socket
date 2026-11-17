package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicOptions
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A core server stopped and replaced inside one [VirtualPair], on the native stack where this runtime has one: a loop
 * stopped twice releases its poller, socket and memory once, so the process survives to the pair's end.
 */
class CoreServerRestartTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun aServerStoppedInsideThePairIsStoppedAgainAtItsEndWithoutAReleaseTwice() =
        VirtualPair.with(options) { pair ->
            pair.stopServer()
            pair.stopServer()
        }

    @Test
    fun aRestartedServerAcceptsTheReconnectedClient() =
        VirtualPair.with(options) { pair ->
            pair.restartServer(options)
            val client = pair.reconnect(options)
            pair.runUntil { client.connection.state.value is com.ditchoom.socket.quic.QuicConnectionState.Established }
            // The server establishes once the client's last handshake flight arrives.
            pair.runFor(pair.oneWay * 4)
            assertTrue(pair.accept().end() is EdgeEnd.Open)
        }
}
