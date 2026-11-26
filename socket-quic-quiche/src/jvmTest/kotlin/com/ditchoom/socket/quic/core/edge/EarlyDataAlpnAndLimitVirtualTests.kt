package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.EarlyDataProtocolNotOfferedException
import com.ditchoom.socket.quic.IncomingStreams
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicEarlyDataOutcome
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.QuicSessionTicketKeys
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.QuicStreamLimit
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The application protocols a 0-RTT client offers, and the first flight it holds while the early-data block runs: a
 * client sending early data offers only the protocol of the session it resumes and fails before it resolves a route when
 * its options do not list it; a block opening past the stream limit its ticket remembers releases the held flight, so the
 * open that waits for the peer's credit completes.
 */
class EarlyDataAlpnAndLimitVirtualTests {
    private fun options(
        alpn: List<String> = listOf(PROTOCOL),
        bidirectional: Long = 100,
    ) = QuicOptions(
        alpnProtocols = alpn,
        verifyPeer = false,
        idleTimeout = 30.seconds,
        enablePmtuDiscovery = false,
        enableEarlyData = true,
        sessionStore = QuicSessionStore.NotKept,
        incomingStreams = IncomingStreams.Accept(bidirectional = QuicStreamLimit(bidirectional.toUInt())),
    )

    private val tls = coreTlsConfig().copy(sessionTicketKeys = QuicSessionTicketKeys.Shared(keyMaterial()))

    @Test
    fun aClientNamingTwoProtocolsOffersOnlyTheSessionsWhenItSendsEarlyData() {
        val ticket = ticketFrom(options())
        VirtualPair.with(options(alpn = listOf(OTHER_PROTOCOL)), tls = tls) { pair ->
            // Control: without early data the client offers both, and the handshake negotiates the server's.
            val control = pair.reconnect(options(alpn = listOf(PROTOCOL, OTHER_PROTOCOL)).copy(resumption = QuicResumption.Resume(ticket)))
            pair.runUntil { control.connection.state.value is QuicConnectionState.Established }
            assertEquals(OTHER_PROTOCOL, control.connection.negotiatedAlpn, "a client offering both negotiates this server's")

            val resumption = QuicResumption.ResumeWithEarlyData(ticket) { openStream().writeFully(text(REQUEST), 1.hours) }
            val client = pair.reconnect(options(alpn = listOf(PROTOCOL, OTHER_PROTOCOL)).copy(resumption = resumption))
            launch { runCatching { client.connection.runEarlyData(resumption, client.offer) { client.connection.established() } } }
            pair.runUntil { client.connection.end() is EdgeEnd.Ended }
            assertTrue(
                client.connection.state.value !is QuicConnectionState.Established,
                "the offer was the session's protocol alone, so a server speaking another has nothing to select",
            )
        }
    }

    @Test
    fun earlyDataForAProtocolTheOptionsDoNotListFailsBeforeAnyRouteIsAsked() {
        val ticket = ticketFrom(options())
        val routesAsked = AtomicInteger()
        val refused =
            kotlinx.coroutines.runBlocking {
                assertFailsWith<EarlyDataProtocolNotOfferedException> {
                    coreConnect(
                        mismatch = MismatchResponse.Fail,
                        host = "127.0.0.1",
                        port = 9,
                        serverName = "localhost",
                        options =
                            options(alpn = listOf(OTHER_PROTOCOL)).copy(
                                resumption = QuicResumption.ResumeWithEarlyData(ticket) { },
                            ),
                        factory = BufferFactory.network(),
                        api = platformQuicheApi(),
                        timeout = 2.seconds,
                        readPolicy = ReadPolicy.Bounded(2.seconds),
                        writePolicy = WritePolicy.Bounded(2.seconds),
                        routeSource = {
                            routesAsked.incrementAndGet()
                            it.routeSourceAddress()
                        },
                    )
                }
            }
        assertEquals(PROTOCOL, refused.sessionProtocol, "the refusal names the session's protocol")
        assertEquals(listOf(OTHER_PROTOCOL), refused.offered, "and what the options list")
        assertEquals(0, routesAsked.get(), "nothing was resolved or opened")
    }

    @Test
    fun anEarlyDataBlockOpeningPastTheRememberedStreamLimitReleasesTheFlightAndEveryOpenCompletes() {
        val ticket = ticketFrom(options(bidirectional = LIMIT))
        VirtualPair.with(options(bidirectional = LIMIT), tls = tls) { pair ->
            pair.accept()
            val serving = serve(pair)
            val opened = ArrayList<QuicByteStream>()
            val resumption =
                QuicResumption.ResumeWithEarlyData(ticket) {
                    // One more than the ticket remembers: the last open waits for credit the peer grants only after
                    // hearing from us, so it must release the held first flight.
                    repeat(LIMIT.toInt() + 1) { index ->
                        val stream = openStream()
                        stream.writeFully(text("$REQUEST-$index"), 1.hours)
                        stream.shutdownSend()
                        opened += stream
                    }
                }
            val client = pair.reconnect(options(bidirectional = LIMIT).copy(resumption = resumption))
            val replies = ArrayList<String>()
            launch {
                client.connection.runEarlyData(resumption, client.offer) { client.connection.established() }
                opened.forEach { replies += it.readText() }
            }
            pair.runUntil(within = 10.seconds) { replies.size == LIMIT.toInt() + 1 }
            assertEquals(List(LIMIT.toInt() + 1) { "ack:$REQUEST-$it" }, replies, "every open completed its exchange")
            assertEquals(
                QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.Accepted),
                client.connection.resumption,
            )
            serving.cancel()
        }
    }

    private fun CoroutineScope.serve(pair: VirtualPair): Job =
        launch {
            while (true) {
                val connection = pair.server.accept()
                launch {
                    while (true) {
                        val stream =
                            try {
                                connection.acceptStream()
                            } catch (ended: QuicCloseException) {
                                return@launch
                            }
                        stream.markReplaySafe()
                        val got = stream.readText()
                        stream.writeFully(text("ack:$got"), 1.hours)
                        stream.shutdownSend()
                    }
                }
            }
        }

    private suspend fun QuicByteStream.readText(): String {
        val out = StringBuilder()
        while (true) {
            when (val chunk = read(1.hours) { it.readString(it.remaining(), Charset.UTF8) }) {
                is ScopedReadResult.Data -> out.append(chunk.value)
                ScopedReadResult.End, ScopedReadResult.Reset -> return out.toString()
            }
        }
    }

    private fun ticketFrom(issuing: QuicOptions): QuicSessionTicket {
        var issued: QuicSessionTicket? = null
        VirtualPair.with(issuing, tls = tls) { pair ->
            pair.runUntil { pair.client.connection.sessionTicket.value is QuicSessionTicketState.Issued }
            issued = assertIs<QuicSessionTicketState.Issued>(pair.client.connection.sessionTicket.value).ticket
        }
        return checkNotNull(issued)
    }

    private fun text(payload: String): ReadBuffer {
        val out = BufferFactory.Default.allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        return out
    }

    private fun keyMaterial(): ReadBuffer {
        val key = BufferFactory.managed().allocate(QuicSessionTicketKeys.Shared.KEY_BYTES)
        repeat(QuicSessionTicketKeys.Shared.KEY_BYTES) { key.writeByte((it * 7 + 3).toByte()) }
        key.resetForRead()
        return key
    }

    private companion object {
        const val PROTOCOL = "resume-test"
        const val OTHER_PROTOCOL = "other-proto"
        const val REQUEST = "early-hello"

        /** The server's initial_max_streams_bidi, which the ticket remembers. */
        const val LIMIT = 2L
    }
}
