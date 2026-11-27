package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.IncomingStreams
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicEarlyDataChange
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.QuicSessionTicketKeys
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamLimit
import com.ditchoom.socket.quic.StreamOpenPolicy
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.DrainUntil
import com.ditchoom.socket.quic.core.stream.StreamKind
import com.ditchoom.socket.quic.core.stream.kindOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unidirectional streams and bounded opens on a real core client and server over a 60 ms round trip, on virtual time:
 * each side opens send-only streams the other receives as receive-only, the peer's unidirectional limit is its own and
 * comes back with MAX_STREAMS, and an open at the limit waits as long as its policy says.
 */
class UniStreamVirtualTests {
    private fun options(
        uni: Long = 100,
        open: StreamOpenPolicy = StreamOpenPolicy.UntilClosed,
    ) = QuicOptions(
        alpnProtocols = listOf("test"),
        verifyPeer = false,
        idleTimeout = 30.seconds,
        enablePmtuDiscovery = false,
        incomingStreams = IncomingStreams.Accept(unidirectional = QuicStreamLimit(uni.toUInt())),
        streamOpenPolicy = open,
    )

    @Test
    fun aSendOnlyStreamArrivesAsReceiveOnlyWithItsBytesAndFinEachWay() =
        VirtualPair.with(options()) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            val ids = ArrayList<Long>()
            for ((opener, peer) in listOf(client to server, server to client)) {
                val opened = opened(pair) { opener.halves.openSendOnly() }
                ids += opened.id.id
                assertEquals(StreamKind.Unidirectional, kindOf(opened.id))
                launch { sendAll(opened, PAYLOAD) }
                var got: ByteArray? = null
                launch {
                    val incoming = assertIs<IncomingStream.ReceiveOnly>(peer.halves.accept())
                    assertEquals(opened.id, incoming.receive.id)
                    got = readToEnd(incoming.receive)
                }
                pair.runUntil { got != null }
                assertContentEquals(payload(PAYLOAD), got, "every byte, then the FIN")
            }
            assertEquals(listOf(2L, 3L), ids, "the client's first unidirectional stream is 2, the server's 3")
        }

    @Test
    fun openUniStreamOpensASendOnlyStreamThePeerReads() =
        VirtualPair.with(options()) { pair ->
            val server = pair.accept()
            var id = QuicStreamId(0L)
            launch {
                val stream = pair.client.connection.openUniStream()
                id = stream.streamId
                stream.writeFully(bytes(PAYLOAD), 1.hours)
                stream.shutdownSend()
            }
            var got: ByteArray? = null
            launch {
                val stream = server.acceptStream()
                val out = ArrayList<Byte>()
                while (true) {
                    when (val r = stream.read(1.hours)) {
                        is ReadResult.Data -> {
                            while (r.buffer.remaining() > 0) out += r.buffer.readByte()
                            r.buffer.freeIfNeeded()
                        }
                        else -> break
                    }
                }
                got = out.toByteArray()
            }
            pair.runUntil { got != null }
            assertTrue(id.isUnidirectional, "the stream opened is unidirectional: $id")
            assertEquals(2L, id.id)
            assertContentEquals(payload(PAYLOAD), got)
        }

    @Test
    fun anOpenAtThePeersUniLimitWaitsForMaxStreamsWhileBidirectionalOpensGoOn() =
        VirtualPair.with(options(uni = 1)) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            val first = opened(pair) { client.halves.openSendOnly() }
            var second: SendHalf? = null
            launch { second = client.halves.openSendOnly() }
            pair.runFor(1.seconds)
            assertEquals(null, second, "the peer allows one unidirectional stream")
            val bidi = opened(pair) { client.halves.openBidirectional() }
            assertEquals(0L, bidi.id.id, "a bidirectional open does not wait behind unidirectional credit")

            launch { sendAll(first, PAYLOAD) }
            launch { readToEnd(assertIs<IncomingStream.ReceiveOnly>(server.halves.accept()).receive) }
            val asked = pair.now()
            pair.runUntil { second != null }
            assertEquals(6L, checkNotNull(second).id.id, "the waiting open took the next id once the peer gave credit")
            assertTrue(pair.now() - asked < 1.seconds.inWholeNanoseconds)
        }

    @Test
    fun aBoundedOpenReturnsWhenCreditArrivesBeforeItsBound() =
        VirtualPair.with(options(uni = 1, open = StreamOpenPolicy.Bounded(10.seconds))) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            val first = opened(pair) { client.halves.openSendOnly() }
            var second: Result<SendHalf>? = null
            launch { second = runCatching { client.halves.openSendOnly() } }
            pair.runFor(500.milliseconds)
            assertEquals(null, second)
            launch { sendAll(first, PAYLOAD) }
            launch { readToEnd(assertIs<IncomingStream.ReceiveOnly>(server.halves.accept()).receive) }
            pair.runUntil(within = 5.seconds) { second != null }
            assertEquals(6L, checkNotNull(second).getOrThrow().id.id)
        }

    @Test
    fun aBoundedOpenEndsAsAPassedDeadlineAtItsBoundAndSpendsNoId() =
        VirtualPair.with(options(uni = 1, open = StreamOpenPolicy.Bounded(BOUND))) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            val first = opened(pair) { client.halves.openSendOnly() }
            val asked = pair.now()
            var failedAt = -1L
            var failure: Throwable? = null
            launch {
                failure = runCatching { client.halves.openSendOnly() }.exceptionOrNull()
                failedAt = pair.now()
            }
            pair.runUntil(within = BOUND * 3) { failedAt >= 0 }
            assertIs<TimeoutCancellationException>(failure, "the same failure a write's passed deadline is")
            val waited = failedAt - asked
            assertTrue(waited >= BOUND.inWholeNanoseconds && waited < BOUND.inWholeNanoseconds + 5 * MS, "waited ${waited / MS} ms")

            launch { sendAll(first, PAYLOAD) }
            launch { readToEnd(assertIs<IncomingStream.ReceiveOnly>(server.halves.accept()).receive) }
            val third = opened(pair) { client.halves.openSendOnly() }
            assertEquals(6L, third.id.id, "the open that passed its bound spent no id")
        }

    @Test
    fun aWaitingOpenIsRefusedAtTheClosePointAndALaterOneAtOnce() =
        VirtualPair.with(options(uni = 1, open = StreamOpenPolicy.Bounded(1.hours))) { pair ->
            pair.accept()
            val client = pair.client.connection
            opened(pair) { client.halves.openSendOnly() }
            var waiting: Throwable? = null
            launch { waiting = runCatching { client.halves.openSendOnly() }.exceptionOrNull() }
            pair.runFor(200.milliseconds)
            val asked = pair.now()
            launch { client.close(CloseChoice.Graceful(10.seconds.inWholeNanoseconds, DrainUntil.Acknowledged)) }
            pair.runUntil { waiting != null }
            assertTrue(pair.now() - asked < MS, "refused at the close point")
            assertEquals(QuicCloseCircumstance.Ended, assertIs<QuicCloseException>(waiting).circumstance)
        }

    /**
     * A client resuming with a ticket from a server that allowed unidirectional streams opens one in 0-RTT; the server it
     * reaches allows none. That server rejects the 0-RTT, its limits not being the ticket's (RFC 9000 section 7.4.1), and
     * the client, which quiche no longer lets keep the stream (RFC 9001 section 4.6.2), cannot replay it within the new
     * limit: it ends the connection with the change, and nothing of the stream reaches the server. Before, quiche re-sent
     * the stream in 1-RTT and the server closed with STREAM_LIMIT_ERROR. The test below is the control.
     */
    @Test
    fun aClientThatOpenedAUniStreamPastTheReachedServersLimitOfZeroEndsWithTheChangeAndSendsNothing() {
        val tls = coreTlsConfig().copy(sessionTicketKeys = QuicSessionTicketKeys.Shared(keyMaterial()))
        val early = options().copy(enableEarlyData = true, sessionStore = QuicSessionStore.NotKept)
        var issued: QuicSessionTicket? = null
        VirtualPair.with(early, tls = tls) { pair ->
            pair.runUntil { pair.client.connection.sessionTicket.value is QuicSessionTicketState.Issued }
            issued = assertIs<QuicSessionTicketState.Issued>(pair.client.connection.sessionTicket.value).ticket
        }
        val ticket = checkNotNull(issued)
        VirtualPair.with(early.copy(incomingStreams = IncomingStreams.Accept(unidirectional = QuicStreamLimit(0u))), tls = tls) { pair ->
            val resumption =
                QuicResumption.ResumeWithEarlyData(ticket) {
                    val stream = openUniStream()
                    stream.writeFully(bytes(PAYLOAD), 1.hours)
                    stream.shutdownSend()
                }
            val client = pair.reconnect(early.copy(resumption = resumption))
            launch { runCatching { client.connection.runEarlyData(resumption, client.offer) { client.connection.established() } } }
            pair.runUntil(within = 15.seconds) { client.connection.end() is EdgeEnd.Ended }
            assertEquals(2L, pair.server.counts.accepted, "the server accepted the first client and the resuming one")
            val end = assertIs<EdgeEnd.Ended>(client.connection.end()).end
            val rejected = assertIs<ConnEnd.EarlyDataRejected>(end, "the client ended: $end")
            assertEquals(QuicEarlyDataChange.UnidirectionalStreams(remembered = 100, granted = 0, opened = 1), rejected.change)
        }
    }

    /** The control for the test above: the same 0-RTT unidirectional stream to a server that allows one is answered. */
    @Test
    fun theSameEarlyUniStreamToAServerThatAllowsOneIsAnswered() {
        val tls = coreTlsConfig().copy(sessionTicketKeys = QuicSessionTicketKeys.Shared(keyMaterial()))
        val early = options().copy(enableEarlyData = true, sessionStore = QuicSessionStore.NotKept)
        var issued: QuicSessionTicket? = null
        VirtualPair.with(early, tls = tls) { pair ->
            pair.runUntil { pair.client.connection.sessionTicket.value is QuicSessionTicketState.Issued }
            issued = assertIs<QuicSessionTicketState.Issued>(pair.client.connection.sessionTicket.value).ticket
        }
        val ticket = checkNotNull(issued)
        VirtualPair.with(early, tls = tls) { pair ->
            val resumption =
                QuicResumption.ResumeWithEarlyData(ticket) {
                    val stream = openUniStream()
                    stream.writeFully(bytes(PAYLOAD), 1.hours)
                    stream.shutdownSend()
                }
            val client = pair.reconnect(early.copy(resumption = resumption))
            launch { client.connection.runEarlyData(resumption, client.offer) { client.connection.established() } }
            pair.runUntil(within = 5.seconds) { client.connection.state.value is QuicConnectionState.Established }
            assertIs<EdgeEnd.Open>(client.connection.end())
        }
    }

    private fun keyMaterial(): ReadBuffer {
        val key = BufferFactory.managed().allocate(QuicSessionTicketKeys.Shared.KEY_BYTES)
        repeat(QuicSessionTicketKeys.Shared.KEY_BYTES) { key.writeByte((it * 7 + 3).toByte()) }
        key.resetForRead()
        return key
    }

    /** Runs [open] to its answer, passing the pair's loops meanwhile. */
    private suspend fun <S : Any> CoroutineScope.opened(
        pair: VirtualPair,
        open: suspend () -> S,
    ): S {
        var made: S? = null
        launch { made = open() }
        pair.runUntil { made != null }
        return checkNotNull(made)
    }

    private suspend fun sendAll(
        send: SendHalf,
        size: Int,
    ) {
        val source = bytes(size)
        while (source.remaining() > 0) send.write(source, 1.hours)
        send.finish()
    }

    private suspend fun readToEnd(receive: ReceiveHalf): ByteArray {
        val out = ArrayList<Byte>()
        while (true) {
            when (val r = receive.read(1.hours)) {
                is ReadResult.Data -> {
                    while (r.buffer.remaining() > 0) out += r.buffer.readByte()
                    r.buffer.freeIfNeeded()
                }
                else -> return out.toByteArray()
            }
        }
    }

    private fun payload(size: Int) = ByteArray(size) { it.toByte() }

    private fun bytes(size: Int) =
        BufferFactory.Default.allocate(size).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        /** quiche's CRYPTO_FAIL. */
        const val QUICHE_ERR_CRYPTO_FAIL = -9
        const val MS = 1_000_000L
        const val PAYLOAD = 3_000
        val BOUND: Duration = 500.milliseconds
    }
}
