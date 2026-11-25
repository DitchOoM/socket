package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.IncomingStreams
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicSessionKey
import com.ditchoom.socket.quic.QuicSessionMemory
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.QuicStreamLimit
import com.ditchoom.socket.quic.StreamOpenPolicy
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * A client that resumes a session, from the default session store, with a server that now grants fewer streams opens
 * only as many as that server grants: the limits a ticket remembers serve 0-RTT only (RFC 9000 section 7.4.1). The
 * server here sends no early data and the client offers none, so the server's handshake parameters are the limits.
 */
class ResumedStreamLimitsVirtualTests {
    private fun options(uni: Long) =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            enablePmtuDiscovery = false,
            incomingStreams = IncomingStreams.Accept(unidirectional = QuicStreamLimit(uni.toUInt())),
            streamOpenPolicy = StreamOpenPolicy.Bounded(1.hours),
            // The process's own memory, which the default store keeps tickets in: the pair keeps tickets of its own
            // only for the default store itself.
            sessionStore = QuicSessionStore.InMemory(QuicSessionMemory.Process),
        )

    @Test
    fun aClientResumedFromTheDefaultStoreWaitsAtTheLimitTheServerItReachedGrants() =
        VirtualPair.with(options(uni = 100)) { pair ->
            pair.runUntil { pair.client.connection.sessionTicket.value is QuicSessionTicketState.Issued }
            val firstKey = QuicSessionKey.of("localhost", pair.server.port, options(uni = 1))
            pair.restartServer(options(uni = 1))
            // The new server took another port: the ticket is moved to its key, as when the system hands a later
            // server the port an earlier one had.
            val ticket = assertIs<QuicSessionTicketState.Issued>(QuicSessionMemory.Process.take(firstKey)).ticket
            QuicSessionMemory.Process.keep(QuicSessionKey.of("localhost", pair.server.port, options(uni = 1)), ticket)
            val client = pair.reconnect(options(uni = 1)).connection
            pair.runUntil { client.state.value is com.ditchoom.socket.quic.QuicConnectionState.Established }
            pair.runFor(pair.oneWay * 4)

            var first = false
            launch {
                client.halves.openSendOnly()
                first = true
            }
            pair.runUntil { first }
            var second = false
            launch {
                runCatching { client.halves.openSendOnly() }
                second = true
            }
            pair.runFor(1.seconds)
            assertEquals(
                false,
                second,
                "the second open waits at the reached server's limit of one stream; resumption ${client.resumption}",
            )
            var report: StallReport? = null
            launch { report = client.stallReport(5.seconds) }
            pair.runUntil { report != null }
            assertEquals(1, assertIs<InsideAnswer.Answered>(report!!.inside).view.opensWaiting)
        }
}
