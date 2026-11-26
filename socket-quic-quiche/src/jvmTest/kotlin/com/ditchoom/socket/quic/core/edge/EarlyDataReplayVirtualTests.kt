package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.IncomingStreams
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicEarlyDataChange
import com.ditchoom.socket.quic.QuicEarlyDataOutcome
import com.ditchoom.socket.quic.QuicEarlyDataRejection
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.QuicSessionTicketKeys
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.QuicStreamLimit
import com.ditchoom.socket.quic.QuicStreamOrigin
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.core.conn.HandshakeFate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * A server that rejects a client's 0-RTT (RFC 9001 §4.6.2) never sees it, and quiche keeps none of it: the core replays
 * the bytes the early-data block wrote on fresh 1-RTT streams when the server kept what they relied on, and ends the
 * connection with [QuicConnectionEnd.EarlyDataRejected] when it did not. The block runs once either way.
 */
class EarlyDataReplayVirtualTests {
    private fun options(
        early: Boolean = true,
        uni: Long = 100,
    ) = QuicOptions(
        alpnProtocols = listOf("test"),
        verifyPeer = false,
        idleTimeout = 30.seconds,
        enablePmtuDiscovery = false,
        enableEarlyData = early,
        sessionStore = QuicSessionStore.NotKept,
        incomingStreams = IncomingStreams.Accept(unidirectional = QuicStreamLimit(uni.toUInt())),
    )

    private val tls = coreTlsConfig().copy(sessionTicketKeys = QuicSessionTicketKeys.Shared(keyMaterial()))

    @Test
    fun aRejectedZeroRttIsReplayedOnceInOneRttWhenTheServerKeptWhatItReliedOn() {
        val ticket = ticketFrom(options())
        var runs = 0
        VirtualPair.with(options(early = false), tls = tls) { pair ->
            pair.accept()
            val serving = serve(pair)
            var early: QuicByteStream? = null
            val resumption =
                QuicResumption.ResumeWithEarlyData(ticket) {
                    runs++
                    val stream = openStream()
                    stream.writeFully(text(REQUEST), 1.hours)
                    stream.shutdownSend()
                    early = stream
                }
            val client = pair.reconnect(options().copy(resumption = resumption))
            var reply = ""
            launch {
                client.connection.runEarlyData(resumption, client.offer) { client.connection.established() }
                reply = checkNotNull(early).readText()
            }
            pair.runUntil { reply.isNotEmpty() }
            pair.runFor(pair.oneWay * 6)
            assertEquals(
                QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.NotAccepted(QuicEarlyDataRejection.DeclinedByPeer)),
                client.connection.resumption,
            )
            assertEquals(listOf(Read(REQUEST, QuicStreamOrigin.Confirmed)), serving.read, "the bytes arrived once, in 1-RTT")
            assertEquals("ack:$REQUEST", reply, "the server answered on the stream the block opened")
            assertEquals(1, runs, "the block ran once")
            var read: QuicStatsRead = QuicStatsRead.Ended
            launch { read = client.connection.stats() }
            pair.runUntil { read is QuicStatsRead.Read }
            val stats = assertIs<QuicStatsRead.Read>(read).stats
            assertEquals(0L, stats.packetsLost, "quiche declared no rejected 0-RTT packet lost")
            assertEquals(0L, stats.streamBytesRetransmitted, "quiche re-sent none of the rejected 0-RTT")
            serving.job.cancel()
        }
    }

    @Test
    fun aRejectedZeroRttWithinTheServersLowerStreamLimitsIsReplayed() {
        val ticket = ticketFrom(options(uni = 100))
        VirtualPair.with(options(uni = 1), tls = tls) { pair ->
            pair.accept()
            val serving = serve(pair, uni = true)
            val resumption = uniStreams(ticket, count = 1)
            val client = pair.reconnect(options(uni = 1).copy(resumption = resumption))
            launch { client.connection.runEarlyData(resumption, client.offer) { client.connection.established() } }
            pair.runUntil { serving.read.isNotEmpty() }
            pair.runFor(pair.oneWay * 6)
            assertIs<QuicEarlyDataOutcome.NotAccepted>(assertIs<QuicResumptionOutcome.Resumed>(client.connection.resumption).earlyData)
            assertEquals(listOf(Read(REQUEST, QuicStreamOrigin.Confirmed)), serving.read)
            assertIs<EdgeEnd.Open>(client.connection.end())
            serving.job.cancel()
        }
    }

    @Test
    fun aRejectedZeroRttOnMoreStreamsThanTheServerNowGrantsEndsTheConnectionUnsent() {
        val ticket = ticketFrom(options(uni = 100))
        VirtualPair.with(options(uni = 1), tls = tls) { pair ->
            pair.accept()
            val serving = serve(pair, uni = true)
            var runs = 0
            val resumption = uniStreams(ticket, count = 2) { runs++ }
            val client = pair.reconnect(options(uni = 1).copy(resumption = resumption))
            var failed: Throwable? = null
            launch {
                failed =
                    runCatching {
                        client.connection.runEarlyData(
                            resumption,
                            client.offer,
                        ) { client.connection.established() }
                    }.exceptionOrNull()
            }
            pair.runUntil { client.connection.end() is EdgeEnd.Ended && failed != null }
            pair.runFor(pair.oneWay * 6)
            val expected =
                QuicConnectionEnd.EarlyDataRejected(
                    QuicEarlyDataRejection.DeclinedByPeer,
                    QuicEarlyDataChange.UnidirectionalStreams(remembered = 100, granted = 1, opened = 2),
                )
            assertEquals(expected, assertIs<QuicCloseException>(failed).end)
            val ended = assertIs<EdgeEnd.Ended>(client.connection.end())
            assertIs<HandshakeFate.NotCompleted>(ended.handshake, "a refused replay ends the connection before its completion counts: the connect fails")
            assertEquals(emptyList(), serving.read, "no early byte reached the server")
            assertEquals(1, runs, "the block ran once")
            serving.job.cancel()
        }
    }

    /** An early-data block that writes [REQUEST] on each of [count] unidirectional streams, after [also]. */
    private fun uniStreams(
        ticket: QuicSessionTicket,
        count: Int,
        also: () -> Unit = {},
    ) = QuicResumption.ResumeWithEarlyData(ticket) {
        also()
        repeat(count) {
            val stream = openUniStream()
            stream.writeFully(text(REQUEST), 1.hours)
            stream.shutdownSend()
        }
    }

    /** A ticket from a server with [issuing] options and the shared keys. */
    private fun ticketFrom(issuing: QuicOptions): QuicSessionTicket {
        var issued: QuicSessionTicket? = null
        VirtualPair.with(issuing, tls = tls) { pair ->
            pair.runUntil { pair.client.connection.sessionTicket.value is QuicSessionTicketState.Issued }
            issued = assertIs<QuicSessionTicketState.Issued>(pair.client.connection.sessionTicket.value).ticket
        }
        return checkNotNull(issued)
    }

    private data class Read(
        val text: String,
        val origin: QuicStreamOrigin,
    )

    private class Serving(
        val job: Job,
        val read: MutableList<Read>,
    )

    /** What every connection the server offers next receives on its streams; a bidirectional one is answered. */
    private fun CoroutineScope.serve(
        pair: VirtualPair,
        uni: Boolean = false,
    ): Serving {
        val read = ArrayList<Read>()
        val job =
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
                            val origin = stream.origin
                            stream.markReplaySafe()
                            val got = stream.readText()
                            read += Read(got, origin)
                            if (!uni) {
                                stream.writeFully(text("ack:$got"), 1.hours)
                                stream.shutdownSend()
                            }
                        }
                    }
                }
            }
        return Serving(job, read)
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
        const val REQUEST = "early-hello"
    }
}
