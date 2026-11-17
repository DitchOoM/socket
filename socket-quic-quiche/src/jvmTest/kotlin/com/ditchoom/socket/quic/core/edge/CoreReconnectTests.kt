package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.HandshakePolicy
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
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
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheSendInfo
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

    @Test
    fun aConnectQuicheNeverLetSendHandsItsTicketBack() {
        val options = base.copy(sessionStore = QuicSessionStore.InMemory(QuicSessionMemory()))
        VirtualPair.with(options) { pair ->
            ticketOf(pair)
            assertEquals(0, endedBeforeItsFirstFlight(pair, options), "the failed connect sent nothing")
            assertIs<QuicResumptionOutcome.Resumed>(connected(pair, options).connection.resumption, "the next connect resumed")
        }
    }

    @Test
    fun anAppCacheGetsBackTheVeryTicketAConnectNeverSent() {
        val cache = RecordingCache()
        val options = base.copy(sessionStore = QuicSessionStore.Supplied(cache))
        VirtualPair.with(options) { pair ->
            val kept = cache.kept()
            endedBeforeItsFirstFlight(pair, options)
            assertEquals(kept, cache.restored())
            assertIs<QuicResumptionOutcome.Resumed>(connected(pair, options).connection.resumption)
        }
    }

    @Test
    fun aTicketWhoseFirstFlightLeftStaysUsedThoughTheConnectFailed() {
        val options = base.copy(sessionStore = QuicSessionStore.InMemory(QuicSessionMemory()))
        VirtualPair.with(options) { pair ->
            ticketOf(pair)
            val client = pair.reconnect(options)
            pair.lose(End.Client, LOST)
            val before = pair.sentBy(End.Client).size
            pair.runUntil { pair.sentBy(End.Client).size > before }
            client.connection.closeNow(QuicError.NoError)
            pair.runUntil { client.connection.end() is EdgeEnd.Ended }
            pair.lose(End.Client, 0)
            assertEquals(
                QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered),
                connected(pair, options).connection.resumption,
                "a ticket that may be on the wire is never offered again",
            )
        }
    }

    // ---- harness ----

    private companion object {
        const val LOST = 1_000
        const val QUICHE_DONE = -1
    }

    /** A store of one ticket whatever the server, which records what it hands out. */
    private class RecordingCache : QuicSessionCache {
        private val slot = AtomicReference<QuicSessionTicketState>(QuicSessionTicketState.NotIssued)
        private val keeps = Channel<QuicSessionTicket>(Channel.UNLIMITED)
        val taken: MutableList<QuicSessionTicketState> = Collections.synchronizedList(ArrayList())

        override fun take(server: QuicSessionKey): QuicSessionTicketState =
            slot.getAndSet(QuicSessionTicketState.NotIssued).also {
                taken +=
                    it
            }

        override fun keep(
            server: QuicSessionKey,
            ticket: QuicSessionTicket,
        ) {
            slot.set(QuicSessionTicketState.Issued(ticket))
            keeps.trySend(ticket)
        }

        override fun restore(
            server: QuicSessionKey,
            ticket: QuicSessionTicket,
        ) {
            slot.compareAndSet(QuicSessionTicketState.NotIssued, QuicSessionTicketState.Issued(ticket))
            restores.trySend(ticket)
        }

        private val restores = Channel<QuicSessionTicket>(Channel.UNLIMITED)

        /** The next ticket kept; keeping runs off the connection's thread, so it is awaited on the wall clock. */
        suspend fun kept(): QuicSessionTicket = withTimeout(10.seconds) { keeps.receive() }

        /** The next ticket handed back, awaited as [kept] is. */
        suspend fun restored(): QuicSessionTicket = withTimeout(10.seconds) { restores.receive() }
    }

    /** quiche gives this client nothing to send. */
    private class NothingToSend(
        private val inner: QuicheApi,
    ) : QuicheApi by inner {
        override fun connSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            sendInfo: QuicheSendInfo,
        ): Int = QUICHE_DONE
    }

    /**
     * A new client with [options] in place of the last, which quiche never lets send, run until its handshake timed out;
     * how many datagrams it sent.
     */
    private suspend fun endedBeforeItsFirstFlight(
        pair: VirtualPair,
        options: QuicOptions,
    ): Int {
        val client = pair.reconnect(options.copy(handshakePolicy = HandshakePolicy.Bounded(1.seconds)), ::NothingToSend)
        val before = pair.sentBy(End.Client).size
        pair.runUntil { client.connection.end() is EdgeEnd.Ended }
        return pair.sentBy(End.Client).size - before
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
