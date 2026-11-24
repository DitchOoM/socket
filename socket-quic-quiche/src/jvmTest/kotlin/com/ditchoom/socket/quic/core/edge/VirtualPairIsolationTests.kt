package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicFullHandshakeReason
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicSessionKey
import com.ditchoom.socket.quic.QuicSessionMemory
import com.ditchoom.socket.quic.QuicSessionTicketState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A pair's client takes no ticket another test left in the process's session store: the system hands a later server
 * the port an earlier one had, and the store keys tickets by name and port (CI: `FullHandshake(TicketNotResumed)` where
 * `NoTicketOffered` was expected, and a second stream opened through a limit of one).
 */
class VirtualPairIsolationTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun aPairsClientOffersNoTicketAnotherTestLeftInTheProcessStore() =
        VirtualPair.with(options) { pair ->
            pair.runUntil { pair.client.connection.sessionTicket.value is QuicSessionTicketState.Issued }
            val ticket = assertIs<QuicSessionTicketState.Issued>(pair.client.connection.sessionTicket.value).ticket
            pair.restartServer(options)
            // Another test's ticket for the port the new server took.
            QuicSessionMemory.Process.keep(QuicSessionKey.of("localhost", pair.server.port, options), ticket)
            val client = pair.reconnect(options).connection
            pair.runUntil { client.state.value is QuicConnectionState.Established }
            assertEquals(QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered), client.resumption)
        }
}
