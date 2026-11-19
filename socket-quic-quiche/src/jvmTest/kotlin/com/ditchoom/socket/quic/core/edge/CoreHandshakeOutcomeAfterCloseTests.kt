package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicFullHandshakeReason
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumptionOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A core connection still says how its handshake went once the peer has closed it: a block that reads
 * `resumption` or `negotiatedAlpn` after a server that replied and returned must not find the state `Closed`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreHandshakeOutcomeAfterCloseTests {
    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            enablePmtuDiscovery = false,
        )

    @Test
    fun theHandshakeOutcomeIsReadableAfterThePeerClosed() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            launch { serverSide.close(QuicError.NoError) }
            pair.runUntil { pair.client.connection.state.value is QuicConnectionState.Closed }
            val connection = pair.client.connection
            assertIs<QuicConnectionState.Closed>(connection.state.value)
            assertEquals(
                QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered),
                connection.resumption,
                "the outcome the handshake settled, read after the peer closed",
            )
            assertEquals("test", connection.negotiatedAlpn)
        }
}
