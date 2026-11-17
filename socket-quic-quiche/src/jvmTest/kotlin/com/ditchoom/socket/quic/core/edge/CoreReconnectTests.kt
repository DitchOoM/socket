package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicFullHandshakeReason
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicSessionCache
import com.ditchoom.socket.quic.QuicSessionKey
import com.ditchoom.socket.quic.QuicSessionMemory
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.core.conn.FirstFlight
import com.ditchoom.socket.quic.core.conn.Offer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * A reconnect to a server the client talked to before resumes with no ticket from the app, and does so privately: no
 * ticket is offered twice, an expired or rejected one is never offered again, nothing is sent as 0-RTT that the app
 * did not write as such, and a client that opts out never resumes.
 */
class CoreReconnectTests {
    private val base =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun aSecondConnectResumesWithNoTicketFromTheAppAndSendsNoZeroRtt() {
        val options = base.copy(sessionStore = QuicSessionStore.InMemory(QuicSessionMemory()), enableEarlyData = true)
        VirtualPair.with(options) { pair ->
            ticketOf(pair)
            val from = pair.now()
            val client = connected(pair, options)

            assertIs<QuicResumptionOutcome.Resumed>(client.connection.resumption, "the reconnect resumed")
            assertEquals(FirstFlight.Immediate, assertIs<Offer.Offered>(client.offer).flight, "its first flight waits for no 0-RTT")
            assertFalse(pair.sentBy(End.Client, from).any { carriesZeroRtt(it.bytes) }, "nothing was sent as 0-RTT")
        }
    }

    @Test
    fun noTicketIsOfferedTwiceAndAnAppOfferDiscardsTheKeptOne() {
        val cache = RecordingCache()
        val options = base.copy(sessionStore = QuicSessionStore.Supplied(cache))
        VirtualPair.with(options) { pair ->
            val first = cache.kept()
            val second = connected(pair, options)
            assertIs<QuicResumptionOutcome.Resumed>(second.connection.resumption)
            val fromSecond = cache.kept()
            val explicit = assertIs<QuicSessionTicketState.Issued>(second.connection.sessionTicket.value).ticket
            assertEquals(fromSecond, explicit, "the store kept the ticket the connection published")

            val third = connected(pair, options.copy(resumption = QuicResumption.Resume(explicit)))
            assertIs<QuicResumptionOutcome.Resumed>(third.connection.resumption)
            val fromThird = cache.kept()
            val fourth = connected(pair, options)
            assertIs<QuicResumptionOutcome.Resumed>(fourth.connection.resumption)

            val taken = cache.taken.filterIsInstance<QuicSessionTicketState.Issued>().map { it.ticket }
            assertEquals(listOf(first, fromSecond, fromThird), taken, "each kept ticket left the store once, in order")
            assertEquals(taken.size, taken.toSet().size, "no ticket left the store twice")
            assertNotEquals(first, fromSecond, "a resumed connection's new ticket replaced the one it used")
        }
    }

    @Test
    fun anExpiredTicketIsDroppedAndTheReconnectIsAFullHandshake() {
        val time = TestTimeSource()
        val options = base.copy(sessionStore = QuicSessionStore.InMemory(QuicSessionMemory(time)))
        VirtualPair.with(options) { pair ->
            ticketOf(pair)
            time += QuicSessionMemory.MAX_AGE + 1.milliseconds
            val stale = connected(pair, options)
            assertEquals(
                QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered),
                stale.connection.resumption,
                "the expired ticket was not offered",
            )
            assertIs<QuicResumptionOutcome.Resumed>(connected(pair, options).connection.resumption, "the fresh ticket resumes")
        }
    }

    @Test
    fun aRejectedTicketFallsBackToAFullHandshakeAndIsNeverOfferedAgain() {
        val cache = RecordingCache()
        val options = base.copy(sessionStore = QuicSessionStore.Supplied(cache))
        VirtualPair.with(options) { pair ->
            val rejected = cache.kept()
            // A server of another ticket key behind the same name: the cache hands it the first server's ticket.
            pair.restartServer(options)
            val full = connected(pair, options)
            assertEquals(
                QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.TicketNotResumed),
                full.connection.resumption,
            )
            val replacement = cache.kept()
            assertIs<QuicResumptionOutcome.Resumed>(connected(pair, options).connection.resumption)

            val taken = cache.taken.filterIsInstance<QuicSessionTicketState.Issued>().map { it.ticket }
            assertEquals(listOf(rejected, replacement), taken, "the rejected ticket was offered once, then replaced")
        }
    }

    @Test
    fun aClientThatOptsOutNeverResumes() {
        val options = base.copy(sessionStore = QuicSessionStore.NotKept)
        VirtualPair.with(options) { pair ->
            ticketOf(pair)
            repeat(2) {
                assertEquals(
                    QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered),
                    connected(pair, options).connection.resumption,
                )
            }
        }
    }

    // ---- harness ----

    /** A store of one ticket whatever the server, which records what it hands out. */
    private class RecordingCache : QuicSessionCache {
        private val slot = AtomicReference<QuicSessionTicketState>(QuicSessionTicketState.NotIssued)
        private val keeps = Channel<QuicSessionTicket>(Channel.UNLIMITED)
        val taken: MutableList<QuicSessionTicketState> = Collections.synchronizedList(ArrayList())

        override fun take(server: QuicSessionKey): QuicSessionTicketState = slot.getAndSet(QuicSessionTicketState.NotIssued).also { taken += it }

        override fun keep(
            server: QuicSessionKey,
            ticket: QuicSessionTicket,
        ) {
            slot.set(QuicSessionTicketState.Issued(ticket))
            keeps.trySend(ticket)
        }

        /** The next ticket kept; keeping runs off the connection's thread, so it is awaited on the wall clock. */
        suspend fun kept(): QuicSessionTicket = withTimeout(10.seconds) { keeps.receive() }
    }

    private suspend fun ticketOf(pair: VirtualPair): QuicSessionTicket {
        pair.runUntil { pair.client.connection.sessionTicket.value is QuicSessionTicketState.Issued }
        return assertIs<QuicSessionTicketState.Issued>(pair.client.connection.sessionTicket.value).ticket
    }

    /** A new client with [options] in place of the last, once its handshake completed and its ticket arrived. */
    private suspend fun connected(
        pair: VirtualPair,
        options: QuicOptions,
    ): com.ditchoom.socket.quic.replay.conditions.ReplayClient {
        val client = pair.reconnect(options)
        pair.runUntil { client.connection.state.value is QuicConnectionState.Established }
        ticketOf(pair)
        return client
    }
}
