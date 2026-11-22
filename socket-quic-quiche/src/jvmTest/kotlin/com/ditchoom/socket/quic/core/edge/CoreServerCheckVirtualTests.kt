package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.ServerCertVerdict
import com.ditchoom.socket.quic.ServerCertVerifier
import com.ditchoom.socket.quic.core.conn.Offer
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.replay.conditions.REPLAY_DEADLINE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Nothing a client writes reaches a server before the server passed its check after the handshake, on a real core
 * client and server on virtual time, through the connect's own [ServerCheck.connected]: an early-data block that runs
 * after the handshake runs only after the check, 0-RTT the handshake did not accept (which quiche sends again as 1-RTT)
 * waits for the check, a resumed connection is checked against its chain like a fresh one, and a chain that could not
 * be copied fails the check, resumed or not.
 */
class CoreServerCheckVirtualTests {
    private fun options(verdict: ServerCertVerdict) =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            enablePmtuDiscovery = false,
            enableEarlyData = true,
            sessionStore = QuicSessionStore.NotKept,
            serverCertVerifiers = listOf(RecordingVerifier(verdict)),
        )

    @Test
    fun anEarlyDataBlockThatRunsAfterTheHandshakeNeverRunsForAServerThatFailsItsCheck() =
        VirtualPair.with(options(TRUSTED)) { pair ->
            pair.accept() // the pair's own first connection, so what the server offers next is the client's under test
            val serving = serve(pair)
            var ran = false
            val refusing = options(REJECTED).copy(resumption = early(unusableTicket()) { ran = true })
            val client = pair.reconnect(refusing)
            assertIs<Offer.Unusable>(client.offer, "quiche refused the ticket: the block runs after the handshake")
            val connect = connectOf(client, refusing)
            pair.runUntil { connect.done() }
            pair.runFor(pair.oneWay * 6)
            assertTrue(connect.outcome.startsWith(REFUSED), connect.outcome)
            assertTrue(!ran, "the block never ran against a server that failed its check")
            assertEquals(emptyList(), serving.read, "no stream byte reached the server")
            serving.job.cancel()
        }

    @Test
    fun theEarlyDataBlockOfAServerThatClosedAtOnceEndsTheSameWhicheverLandsFirst() {
        val closeFirst = blockAgainstAClosingServer(CloseOrder.BeforeTheBlock)
        val blockFirst = blockAgainstAClosingServer(CloseOrder.AfterTheConnect)
        assertEquals(blockFirst, closeFirst, "the connect and its first operation do not depend on when the close landed")
        assertEquals(RETURNED, closeFirst.first)
        assertEquals<QuicCloseReason>(QuicCloseReason.ByPeer(QuicError.ApplicationError(APP_CODE)), closeFirst.second)
    }

    @Test
    fun zeroRttAFullHandshakeDeclinedWaitsForTheCheckAndNeverReachesAServerThatFailsIt() {
        val ticket = ticketFromAnotherServer()
        VirtualPair.with(options(TRUSTED)) { pair ->
            pair.accept() // the pair's own first connection, so what the server offers next is the client's under test
            val serving = serve(pair)
            // The check takes long enough for anything the client sent at completion to reach the server first.
            val answer = CompletableDeferred<Unit>()
            val slow = ServerCertVerifier { answer.await().let { REJECTED } }
            val refusing = options(REJECTED).copy(serverCertVerifiers = listOf(slow), resumption = early(ticket) {})
            val client = pair.reconnect(refusing)
            assertIs<Offer.Offered>(client.offer, "quiche took the ticket: the request goes as 0-RTT")
            val connect = connectOf(client, refusing)
            pair.runUntil { client.connection.state.value is QuicConnectionState.Established }
            pair.runFor(pair.oneWay * 6)
            answer.complete(Unit)
            pair.runUntil { connect.done() }
            pair.runFor(pair.oneWay * 6)
            assertTrue(connect.outcome.startsWith(REFUSED), connect.outcome)
            assertEquals(
                QuicResumptionOutcome.FullHandshake(com.ditchoom.socket.quic.QuicFullHandshakeReason.TicketNotResumed),
                client.connection.resumption,
            )
            assertEquals(emptyList(), serving.read, "the declined 0-RTT was not sent again to a server that failed its check")
            serving.job.cancel()
        }
    }

    @Test
    fun zeroRttAFullHandshakeDeclinedReachesATrustedServerOnceTheCheckPassed() {
        val ticket = ticketFromAnotherServer()
        VirtualPair.with(options(TRUSTED)) { pair ->
            pair.accept() // the pair's own first connection, so what the server offers next is the client's under test
            val serving = serve(pair)
            val trusting = options(TRUSTED).copy(resumption = early(ticket) {})
            val client = pair.reconnect(trusting)
            val connect = connectOf(client, trusting)
            pair.runUntil { connect.done() && serving.read.isNotEmpty() }
            assertEquals(RETURNED, connect.outcome)
            assertEquals(listOf(REQUEST), serving.read, "the declined 0-RTT arrived once, after the check passed")
            serving.job.cancel()
        }
    }

    @Test
    fun aResumedConnectionIsCheckedAgainstItsChainAndFailsClosedWhenItCannotBeCopied() =
        VirtualPair.with(options(TRUSTED)) { pair ->
            val ticket = ticketOf(pair)
            val verifier = RecordingVerifier(TRUSTED)
            val resumed = options(TRUSTED).copy(serverCertVerifiers = listOf(verifier), resumption = QuicResumption.Resume(ticket))
            val client = pair.reconnect(resumed)
            val connect = connectOf(client, resumed)
            pair.runUntil { connect.done() }
            assertEquals(RETURNED, connect.outcome)
            assertIs<QuicResumptionOutcome.Resumed>(client.connection.resumption)
            assertTrue(verifier.leafBytes > 0, "a resumed session carries its server's chain, and the verifier read it")

            val cut = pair.reconnect(resumed, wrapApi = { ShortChain(it) })
            val failed = connectOf(cut, resumed)
            pair.runUntil { failed.done() }
            assertIs<QuicResumptionOutcome.Resumed>(cut.connection.resumption)
            assertTrue(
                failed.outcome.startsWith(REFUSED),
                "a resumed connection whose chain was not copied fails closed: ${failed.outcome}",
            )
        }

    /** quiche says a certificate is there and then hands over less of it. */
    private class ShortChain(
        private val inner: QuicheApi,
    ) : QuicheApi by inner {
        override fun connPeerCertChainAt(
            conn: QuicheConn,
            index: Int,
            buf: Long,
            bufLen: Int,
        ): Int {
            val length = inner.connPeerCertChainAt(conn, index, buf, bufLen)
            return if (bufLen > 0 && length > 1) length - 1 else length
        }
    }

    private enum class CloseOrder { BeforeTheBlock, AfterTheConnect }

    /**
     * A client whose ticket quiche refused, so its early-data block runs after the handshake and the check, against a
     * server that closes the moment it is offered the connection: the connect's outcome and its first operation's close.
     */
    private fun blockAgainstAClosingServer(order: CloseOrder): Pair<String, QuicCloseReason> {
        var seen: Pair<String, QuicCloseReason>? = null
        VirtualPair.with(options(TRUSTED)) { pair ->
            pair.accept() // the pair's own first connection, so what the server offers next is the client's under test
            val writing = options(TRUSTED).copy(resumption = early(unusableTicket()) {})
            val client = pair.reconnect(writing)
            var closed = false
            val closing = {
                launch {
                    pair.accept().close(QuicError.ApplicationError(APP_CODE))
                    closed = true
                }
            }
            val connect =
                when (order) {
                    CloseOrder.BeforeTheBlock -> {
                        closing()
                        pair.runUntil { closed && client.connection.end() is EdgeEnd.Ended }
                        connectOf(client, writing).also { pair.runUntil { it.done() } }
                    }
                    CloseOrder.AfterTheConnect -> {
                        val connect = connectOf(client, writing).also { pair.runUntil { it.done() } }
                        closing()
                        pair.runUntil { closed && client.connection.end() is EdgeEnd.Ended }
                        connect
                    }
                }
            seen = connect.outcome to assertFailsWith<QuicCloseException> { client.connection.openStream() }.closeReason
        }
        return checkNotNull(seen)
    }

    /** A ticket issued by a server of other ticket keys: quiche takes it, the server here cannot resume it. */
    private fun ticketFromAnotherServer(): QuicSessionTicket {
        var issued: QuicSessionTicket? = null
        VirtualPair.with(options(TRUSTED)) { pair -> issued = ticketOf(pair) }
        return checkNotNull(issued)
    }

    private suspend fun ticketOf(pair: VirtualPair): QuicSessionTicket {
        pair.runUntil { pair.client.connection.sessionTicket.value is QuicSessionTicketState.Issued }
        return assertIs<QuicSessionTicketState.Issued>(pair.client.connection.sessionTicket.value).ticket
    }

    /** Bytes that are not a ticket: quiche refuses them, so there is no 0-RTT. */
    private fun unusableTicket(): QuicSessionTicket = QuicSessionTicket.restore(text("not a ticket"), "test")

    /** An early-data block that writes [REQUEST] on a stream of its own and finishes it, after [also]. */
    private fun early(
        ticket: QuicSessionTicket,
        also: () -> Unit,
    ) = QuicResumption.ResumeWithEarlyData(ticket) {
        also()
        val stream = openStream()
        stream.writeFully(text(REQUEST), REPLAY_DEADLINE)
        stream.shutdownSend()
    }

    /** What every connection the server offers received on its streams. */
    private class Serving(
        val job: Job,
        val read: MutableList<String>,
        val offered: IntArray,
    )

    private fun CoroutineScope.serve(pair: VirtualPair): Serving {
        val read = ArrayList<String>()
        val offered = IntArray(1)
        val job =
            launch {
                while (true) {
                    val connection = pair.server.accept()
                    offered[0]++
                    launch {
                        val stream =
                            try {
                                connection.acceptStream()
                            } catch (ended: QuicCloseException) {
                                return@launch
                            }
                        stream.markReplaySafe()
                        val out = StringBuilder()
                        while (true) {
                            when (val chunk = stream.read(REPLAY_DEADLINE) { it.readString(it.remaining(), Charset.UTF8) }) {
                                is ScopedRead.Data -> out.append(chunk.value)
                                ScopedRead.End, ScopedRead.Reset -> break
                            }
                        }
                        read += out.toString()
                    }
                }
            }
        return Serving(job, read, offered)
    }

    private fun text(payload: String): ReadBuffer {
        val out = BufferFactory.Default.allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        return out
    }

    private companion object {
        const val APP_CODE = 0x2AL
        const val REQUEST = "early-hello"
        val TRUSTED: ServerCertVerdict = ServerCertVerdict.Trusted
        val REJECTED: ServerCertVerdict = ServerCertVerdict.Rejected("not ours")
    }
}
