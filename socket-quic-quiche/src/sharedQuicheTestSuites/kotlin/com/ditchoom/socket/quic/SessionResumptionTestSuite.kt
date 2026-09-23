package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.managed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Session resumption and 0-RTT across a reconnect, on a real quiche client and server over loopback.
 *
 * Every test reconnects the way a peer whose path is gone for good does: a first connection receives a
 * session ticket, closes, and a second connection offers that ticket with a
 * [QuicResumption.ResumeWithEarlyData] block. What the handshake did is read from both ends — the client's [QuicScope.resumption] and the
 * server's, each quiche's own report from its own side of the TLS handshake — and the early bytes are
 * counted where the server application reads them.
 *
 * A suite with per-platform members because each backend binds the session FFI separately: JVM (JNI by
 * default, FFM with `-PquicheJvmBackend=ffm`), Apple cinterop, Linux cinterop and Android JNI.
 */
abstract class SessionResumptionTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Same hook as the other suites: the JVM/Android members turn a missing native into a typed skip. */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private val clientOptions =
        QuicOptions(
            alpnProtocols = listOf(PROTOCOL),
            verifyPeer = false,
            idleTimeout = 10.seconds,
            migration = MigrationPolicy.Manual,
        )

    private val acceptsEarlyData = clientOptions.copy(enableEarlyData = true)

    /** A client that would happily speak either protocol — the list an early-data connection narrows. */
    private val bothProtocols = clientOptions.copy(alpnProtocols = listOf(PROTOCOL, OTHER_PROTOCOL))

    @Test
    fun aReconnectResumesTheSessionAndItsEarlyDataIsAcceptedAsZeroRtt() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = acceptsEarlyData) {
                    val seen = serveEach(this)
                    try {
                        val ticket = firstConnection(port)
                        val first = seen.next()
                        assertEquals(
                            QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered),
                            first.resumption,
                            "the first connection offered no ticket to a server with 0-RTT enabled, so the server must report NoTicketOffered. Saw: $first",
                        )

                        // The block takes its time before writing. Nothing may leave until it returns, so
                        // the bytes must still travel in the first flight; were the flight sent first, the
                        // handshake would complete during the wait and the bytes would go as 1-RTT.
                        val reconnect = earlyReconnect(port, ticket, beforeWrite = { delay(EARLY_BLOCK_WORK) })
                        val second = seen.next()
                        assertEquals(
                            QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.Accepted),
                            reconnect.resumption,
                            "the client offered the ticket this same server issued, with 0-RTT data, to a server that " +
                                "accepts early data — its own handshake must report the session resumed and 0-RTT " +
                                "accepted. Client saw $reconnect; server saw $second",
                        )
                        assertEquals(
                            QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.Accepted),
                            second.resumption,
                            "the server's side of the same handshake must agree: resumed, 0-RTT accepted. Saw: $second",
                        )
                        assertIs<QuicheDriver.EarlyStreamData.Received>(
                            second.earlyStreamData,
                            "0-RTT means the server had the early bytes before its handshake completed; its driver saw " +
                                "no stream readable while handshaking, so the bytes did not travel in the first flight. " +
                                "Saw: $second",
                        )
                        assertEquals(
                            EARLY_PAYLOAD,
                            second.payload,
                            "the early bytes must reach the server application exactly once. Saw: $second",
                        )
                        assertEquals("ack:$EARLY_PAYLOAD", reconnect.reply, "the reply on the early stream. Client saw $reconnect")
                    } finally {
                        seen.job.cancel()
                    }
                }
            }
        }

    @Test
    fun resumingWithoutEarlyDataSkipsTheCertificateButSendsNothingEarly() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = acceptsEarlyData) {
                    val seen = serveEach(this)
                    try {
                        val ticket = firstConnection(port)
                        seen.next()
                        val outcome =
                            withQuicConnection(
                                "127.0.0.1",
                                port,
                                clientOptions.copy(resumption = QuicResumption.Resume(ticket)),
                                timeout = 10.seconds,
                            ) {
                                openStream().exchange(LATE_PAYLOAD)
                                resumption
                            }
                        val second = seen.next()
                        assertEquals(
                            QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.NotAccepted(QuicEarlyDataRejection.NotEnabled)),
                            outcome,
                            "QuicResumption.Resume resumes without enabling 0-RTT on the client. Server saw $second",
                        )
                        assertEquals(
                            QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.NotAccepted(QuicEarlyDataRejection.DeclinedByPeer)),
                            second.resumption,
                            "the server resumed a client that offered no 0-RTT. Saw: $second",
                        )
                        assertEquals(QuicheDriver.EarlyStreamData.None, second.earlyStreamData, "nothing may be sent early. Saw: $second")
                    } finally {
                        seen.job.cancel()
                    }
                }
            }
        }

    @Test
    fun aServerThatDeclinesZeroRttStillResumesAndTheEarlyBytesArriveOnceAfterTheHandshake() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                val sharedKeys = QuicSessionTicketKeys.Shared(keyMaterial())
                val ticket =
                    withQuicServer(
                        port = 0,
                        tlsConfig = testTlsConfig().copy(sessionTicketKeys = sharedKeys),
                        quicOptions = acceptsEarlyData,
                    ) {
                        val seen = serveEach(this)
                        try {
                            firstConnection(port).also { seen.next() }
                        } finally {
                            seen.job.cancel()
                        }
                    }
                // Same key, so the ticket still decrypts; early data off, so 0-RTT is declined.
                withQuicServer(port = 0, tlsConfig = testTlsConfig().copy(sessionTicketKeys = sharedKeys), quicOptions = clientOptions) {
                    val seen = serveEach(this)
                    try {
                        val reconnect = earlyReconnect(port, ticket)
                        val second = seen.next()
                        assertEquals(
                            QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.NotAccepted(QuicEarlyDataRejection.DeclinedByPeer)),
                            reconnect.resumption,
                            "a server holding the ticket's key resumes the session, and one without early data declines " +
                                "0-RTT — the client must report exactly that. Client saw $reconnect; server saw $second",
                        )
                        assertEquals(
                            QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.NotAccepted(QuicEarlyDataRejection.NotEnabled)),
                            second.resumption,
                            "the declining server's own report. Saw: $second",
                        )
                        assertEquals(
                            QuicheDriver.EarlyStreamData.None,
                            second.earlyStreamData,
                            "a server that declined 0-RTT must not process 0-RTT packets (RFC 9001 §4.6.2). Saw: $second",
                        )
                        assertEquals(
                            EARLY_PAYLOAD,
                            second.payload,
                            "declined 0-RTT data is sent again after the handshake, so it arrives exactly once. Saw: $second",
                        )
                        assertEquals("ack:$EARLY_PAYLOAD", reconnect.reply, "Client saw $reconnect")
                    } finally {
                        seen.job.cancel()
                    }
                }
            }
        }

    @Test
    fun aServerThatCannotDecryptTheTicketDoesAFullHandshakeAndTheEarlyBytesArriveOnce() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                val ticket =
                    withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = acceptsEarlyData) {
                        val seen = serveEach(this)
                        try {
                            firstConnection(port).also { seen.next() }
                        } finally {
                            seen.job.cancel()
                        }
                    }
                // A new server generates its own ticket key: the old ticket is undecryptable to it.
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = acceptsEarlyData) {
                    val seen = serveEach(this)
                    try {
                        val reconnect = earlyReconnect(port, ticket)
                        val second = seen.next()
                        assertEquals(
                            QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.TicketNotResumed),
                            reconnect.resumption,
                            "a ticket encrypted under another server's key cannot be resumed; the handshake is full. " +
                                "Client saw $reconnect; server saw $second",
                        )
                        assertEquals(
                            QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.TicketNotResumed),
                            second.resumption,
                            "the server's own report. Saw: $second",
                        )
                        assertEquals(QuicheDriver.EarlyStreamData.None, second.earlyStreamData, "Saw: $second")
                        assertEquals(EARLY_PAYLOAD, second.payload, "the early bytes still arrive exactly once. Saw: $second")
                        assertEquals("ack:$EARLY_PAYLOAD", reconnect.reply, "Client saw $reconnect")
                    } finally {
                        seen.job.cancel()
                    }
                }
            }
        }

    @Test
    fun aTicketTheEngineCannotLoadIsReportedAndTheEarlyBytesArriveOnceAfterTheHandshake() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = acceptsEarlyData) {
                    val seen = serveEach(this)
                    try {
                        val garbage = QuicSessionTicket.restore(text("not a session ticket"), clientOptions.alpnProtocols.single())
                        val reconnect = earlyReconnect(port, garbage)
                        val second = seen.next()
                        assertIs<QuicFullHandshakeReason.TicketUnusable>(
                            assertIs<QuicResumptionOutcome.FullHandshake>(reconnect.resumption, "Client saw $reconnect").reason,
                            "bytes that are not a ticket must be reported as unusable, not silently dropped. " +
                                "Client saw $reconnect; server saw $second",
                        )
                        assertEquals(
                            QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered),
                            second.resumption,
                            "nothing was offered. Saw: $second",
                        )
                        assertEquals(EARLY_PAYLOAD, second.payload, "the early block still runs, once. Saw: $second")
                    } finally {
                        seen.job.cancel()
                    }
                }
            }
        }

    @Test
    fun earlyDataOffersTheSessionsProtocolAndNothingElseTheOptionsList() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                val ticket =
                    withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = acceptsEarlyData) {
                        val seen = serveEach(this)
                        try {
                            firstConnection(port).also { seen.next() }
                        } finally {
                            seen.job.cancel()
                        }
                    }
                assertEquals(PROTOCOL, ticket.protocol, "the ticket names the protocol its session speaks")

                // A server that speaks only the OTHER protocol this client lists.
                val otherOnly = acceptsEarlyData.copy(alpnProtocols = listOf(OTHER_PROTOCOL))
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = otherOnly) {
                    val seen = serveEach(this)
                    try {
                        // Control: without early data the client offers both, so the handshake negotiates
                        // the protocol this server speaks. That is what the narrowing below removes.
                        val negotiated =
                            withQuicConnection(
                                "127.0.0.1",
                                port,
                                bothProtocols.copy(resumption = QuicResumption.Resume(ticket)),
                                timeout = 10.seconds,
                            ) { negotiatedAlpn }
                        assertEquals(OTHER_PROTOCOL, negotiated, "a client offering both protocols negotiates this server's")

                        // With early data the offer is the session's protocol alone, so there is nothing
                        // for this server to select and the handshake fails instead of carrying the early
                        // bytes under a protocol they were not written for.
                        val failure =
                            assertFailsWith<QuicCloseException>(
                                "the client offered $OTHER_PROTOCOL as well, so a handshake that succeeds here " +
                                    "means the early bytes were about to be read as $OTHER_PROTOCOL",
                            ) {
                                earlyReconnect(port, ticket, base = bothProtocols)
                            }
                        assertTrue(
                            failure.closeReason is QuicCloseReason.ByLocal || failure.closeReason is QuicCloseReason.ByPeer,
                            "the handshake must fail over the protocol mismatch, with a reason naming a side. Saw: ${failure.closeReason}",
                        )
                    } finally {
                        seen.job.cancel()
                    }
                }
            }
        }

    @Test
    fun earlyDataForAProtocolTheOptionsDoNotListFailsBeforeAnythingIsSent() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                val ticket =
                    withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = acceptsEarlyData) {
                        val seen = serveEach(this)
                        try {
                            firstConnection(port).also { seen.next() }
                        } finally {
                            seen.job.cancel()
                        }
                    }
                // Nothing listens on this port: reaching it at all would mean the mismatch was not caught
                // where both the ticket and the options are first known.
                val refused =
                    assertFailsWith<EarlyDataProtocolNotOfferedException> {
                        withQuicConnection(
                            "127.0.0.1",
                            DEAD_PORT,
                            clientOptions.copy(
                                alpnProtocols = listOf(OTHER_PROTOCOL),
                                resumption = QuicResumption.ResumeWithEarlyData(ticket) { openStream().writeText(EARLY_PAYLOAD) },
                            ),
                            timeout = 10.seconds,
                        ) { }
                    }
                assertEquals(PROTOCOL, refused.sessionProtocol, "the refusal names the session's protocol")
                assertEquals(listOf(OTHER_PROTOCOL), refused.offered, "…and what this connection was told it may speak")
            }
        }

    // ---- the exchange ---------------------------------------------------------------------------

    /** What the server application saw on one accepted connection. */
    private data class ServerSeen(
        val resumption: QuicResumptionOutcome,
        val earlyStreamData: QuicheDriver.EarlyStreamData,
        val payload: String,
    )

    /** What the reconnecting client saw. */
    private data class Reconnect(
        val resumption: QuicResumptionOutcome,
        val reply: String,
    )

    private class Served(
        val job: kotlinx.coroutines.Job,
        private val observations: Channel<ServerSeen>,
    ) {
        suspend fun next(): ServerSeen = withTimeout(15.seconds) { observations.receive() }
    }

    /**
     * Serve every connection: read one stream to its end, reply `ack:<payload>`, and ship what was seen
     * out of the handler — an assertion thrown inside a launched handler would cancel its siblings and
     * hang the client instead of reporting.
     */
    private fun CoroutineScope.serveEach(server: QuicServer): Served {
        val observations = Channel<ServerSeen>(Channel.UNLIMITED)
        val job =
            launch {
                server.connections {
                    val stream = acceptStream()
                    val payload = stream.readToEnd()
                    stream.writeText("ack:$payload")
                    stream.shutdownSend()
                    val driver = (this as QuicheBackedConnection).quicheDriver
                    observations.send(ServerSeen(resumption, driver.earlyStreamData, payload))
                }
            }
        return Served(job, observations)
    }

    /** A full-handshake connection that exchanges once and returns the ticket the server issued it. */
    private suspend fun firstConnection(port: Int): QuicSessionTicket =
        withQuicConnection("127.0.0.1", port, clientOptions, timeout = 10.seconds) {
            assertEquals(QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered), resumption, "no ticket was offered")
            openStream().exchange(FIRST_PAYLOAD)
            val issued = withTimeout(10.seconds) { sessionTicket.first { it is QuicSessionTicketState.Issued } }
            assertIs<QuicSessionTicketState.Issued>(issued).ticket
        }

    /** Reconnect offering [ticket], with the payload written in a [QuicResumption.ResumeWithEarlyData] block. */
    private suspend fun earlyReconnect(
        port: Int,
        ticket: QuicSessionTicket,
        beforeWrite: suspend () -> Unit = {},
        base: QuicOptions = clientOptions,
    ): Reconnect {
        val early = CompletableDeferred<QuicByteStream>()
        val options =
            base.copy(
                resumption =
                    QuicResumption.ResumeWithEarlyData(ticket) {
                        val stream = openStream()
                        beforeWrite()
                        stream.writeText(EARLY_PAYLOAD)
                        stream.shutdownSend()
                        early.complete(stream)
                    },
            )
        return withQuicConnection("127.0.0.1", port, options, timeout = 10.seconds) {
            val stream = withTimeout(10.seconds) { early.await() }
            Reconnect(resumption, stream.readToEnd())
        }
    }

    private suspend fun QuicByteStream.exchange(payload: String): String {
        writeText(payload)
        shutdownSend()
        return readToEnd()
    }

    private suspend fun QuicByteStream.writeText(payload: String) {
        writeFully(text(payload), STREAM_DEADLINE)
    }

    private suspend fun QuicByteStream.readToEnd(): String {
        val sb = StringBuilder()
        while (true) {
            when (val chunk = read(STREAM_DEADLINE) { it.readString(it.remaining(), Charset.UTF8) }) {
                is ScopedRead.Data -> sb.append(chunk.value)
                ScopedRead.End, ScopedRead.Reset -> return sb.toString()
            }
        }
    }

    private fun text(payload: String): ReadBuffer {
        val out = BufferFactory.deterministic().allocate(payload.length)
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

        /** Nothing listens here: a connect that reaches the network is a connect that checked too late. */
        const val DEAD_PORT = 1
        const val FIRST_PAYLOAD = "first"
        const val EARLY_PAYLOAD = "early-hello"
        const val LATE_PAYLOAD = "late-hello"
        val STREAM_DEADLINE: Duration = 10.seconds

        /** Far longer than a loopback handshake takes, so a first flight sent early would complete it. */
        val EARLY_BLOCK_WORK: Duration = 300.milliseconds
    }
}
