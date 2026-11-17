package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.managed
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicEarlyDataOutcome
import com.ditchoom.socket.quic.QuicEarlyDataRejection
import com.ditchoom.socket.quic.QuicFullHandshakeReason
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.QuicSessionTicketKeys
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.QuicStreamOrigin
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.replay.conditions.REPLAY_DEADLINE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Session resumption and 0-RTT on a core client and a core server over a 60 ms round trip, on virtual time: a resumed
 * request written as 0-RTT is answered one round trip sooner than the same request on a fresh connection, a server that
 * does not mark the early stream replay-safe answers only after the handshake completes, and 0-RTT a server declines
 * arrives once, as 1-RTT.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreResumptionVirtualTests {
    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            enablePmtuDiscovery = false,
            enableEarlyData = true,
            // Each test offers the tickets it means to: a fresh connection here is one that offers none.
            sessionStore = QuicSessionStore.NotKept,
        )

    @Test
    fun aRequestWrittenAsZeroRttIsAnsweredOneRoundTripSoonerThanOnAFreshConnection() =
        VirtualPair.with(options) { pair ->
            val serving = serve(pair, ReplaySafety.MarkedSafe)
            val ticket = ticketOf(pair)

            val fresh = timedRequest(pair, Request.Fresh(options))
            val resumed = timedRequest(pair, early(ticket))

            assertEquals(QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.NoTicketOffered), fresh.outcome)
            assertEquals(QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.Accepted), resumed.outcome)
            assertEquals("ack:$REQUEST", fresh.reply)
            assertEquals("ack:$REQUEST", resumed.reply)
            assertTrue(fresh.nanos >= 2 * RTT_NANOS, "a fresh request takes the handshake's round trip and its own: ${fresh.ms()}")
            assertTrue(resumed.nanos < RTT_NANOS + SLACK_NANOS, "a 0-RTT request is answered in one round trip: ${resumed.ms()}")
            assertTrue(
                fresh.nanos - resumed.nanos >= RTT_NANOS - SLACK_NANOS,
                "resumption saved a round trip: fresh ${fresh.ms()}, resumed ${resumed.ms()}",
            )
            assertEquals(listOf(REQUEST, REQUEST), serving.read, "each request reached the server once")
            serving.job.cancel()
        }

    @Test
    fun anEarlyStreamNotMarkedReplaySafeIsReadOnlyOnceTheHandshakeCompletes() =
        VirtualPair.with(options) { pair ->
            val serving = serve(pair, ReplaySafety.Default)
            val ticket = ticketOf(pair)
            val resumed = timedRequest(pair, early(ticket))

            assertEquals(QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.Accepted), resumed.outcome)
            assertEquals(listOf(Origin.Early), serving.origins, "the request arrived as 0-RTT")
            assertTrue(
                resumed.nanos >= 2 * RTT_NANOS,
                "the server read the replayable request only after the client's Finished arrived: ${resumed.ms()}",
            )
            assertEquals("ack:$REQUEST", resumed.reply)
            assertEquals(listOf(REQUEST), serving.read)
            serving.job.cancel()
        }

    @Test
    fun zeroRttTheServerDeclinesArrivesExactlyOnceAsOneRtt() {
        val keys = QuicSessionTicketKeys.Shared(keyMaterial())
        val tls = coreTlsConfig().copy(sessionTicketKeys = keys)
        var issued: QuicSessionTicket? = null
        VirtualPair.with(options, tls = tls) { pair -> issued = ticketOf(pair) }
        val ticket = assertNotNull(issued)
        // The same key, so the ticket still resumes; early data off, so its 0-RTT is declined.
        VirtualPair.with(options.copy(enableEarlyData = false), tls = tls) { pair ->
            val serving = serve(pair, ReplaySafety.MarkedSafe)
            val sentFrom = pair.now()
            val resumed = timedRequest(pair, early(ticket))

            assertEquals(
                QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.NotAccepted(QuicEarlyDataRejection.DeclinedByPeer)),
                resumed.outcome,
            )
            assertTrue(pair.sentBy(End.Client, sentFrom).any { carriesZeroRtt(it.bytes) }, "the client wrote its request as 0-RTT")
            assertEquals(listOf(REQUEST), serving.read, "the declined request arrived once, after the handshake")
            assertEquals(listOf(Origin.Confirmed), serving.origins)
            assertEquals("ack:$REQUEST", resumed.reply)
            serving.job.cancel()
        }
    }

    @Test
    fun aTicketOfferedToAnotherServerIsAFullHandshakeAndItsEarlyRequestArrivesOnce() {
        var issued: QuicSessionTicket? = null
        VirtualPair.with(options) { pair -> issued = ticketOf(pair) }
        val ticket = assertNotNull(issued)
        // A server of its own ticket key cannot open the first server's ticket.
        VirtualPair.with(options) { pair ->
            val serving = serve(pair, ReplaySafety.MarkedSafe)
            val resumed = timedRequest(pair, early(ticket))

            assertEquals(QuicResumptionOutcome.FullHandshake(QuicFullHandshakeReason.TicketNotResumed), resumed.outcome)
            assertEquals(listOf(REQUEST), serving.read, "the request the client wrote as 0-RTT arrived once, after the handshake")
            assertEquals("ack:$REQUEST", resumed.reply)
            serving.job.cancel()
        }
    }

    // ---- harness ----

    private enum class ReplaySafety { Default, MarkedSafe }

    private enum class Origin { Early, Confirmed }

    /** What the server's handlers saw: each request's bytes and the origin of its stream, in arrival order. */
    private class Serving(
        val job: Job,
        val read: MutableList<String>,
        val origins: MutableList<Origin>,
    )

    /** Every connection the server offers answers each stream's request with `ack:` and the request. */
    private fun CoroutineScope.serve(
        pair: VirtualPair,
        safety: ReplaySafety,
    ): Serving {
        val read = ArrayList<String>()
        val origins = ArrayList<Origin>()
        val job =
            launch {
                while (true) {
                    val connection = pair.server.accept()
                    launch {
                        val stream = connection.acceptStream()
                        origins += if (stream.origin is QuicStreamOrigin.Early) Origin.Early else Origin.Confirmed
                        if (safety == ReplaySafety.MarkedSafe) stream.markReplaySafe()
                        val request = stream.readText()
                        read += request
                        stream.writeFully(text("ack:$request"), REPLAY_DEADLINE)
                        stream.shutdownSend()
                    }
                }
            }
        return Serving(job, read, origins)
    }

    private suspend fun ticketOf(pair: VirtualPair): QuicSessionTicket {
        pair.runUntil { pair.client.connection.sessionTicket.value is QuicSessionTicketState.Issued }
        return assertIs<QuicSessionTicketState.Issued>(pair.client.connection.sessionTicket.value).ticket
    }

    /** What a timed request connects with, and where its stream comes from. */
    private sealed interface Request {
        val options: QuicOptions

        /** A connection with no ticket: the request goes once it is established. */
        class Fresh(
            override val options: QuicOptions,
        ) : Request

        /** A resumption whose 0-RTT block writes the request and hands its stream to [stream]. */
        class Early(
            override val options: QuicOptions,
            val stream: CompletableDeferred<QuicByteStream>,
        ) : Request
    }

    /** Resumes [ticket] with the request written as 0-RTT. */
    private fun early(ticket: QuicSessionTicket): Request.Early {
        val written = CompletableDeferred<QuicByteStream>()
        val resumption =
            QuicResumption.ResumeWithEarlyData(ticket) {
                val stream = openStream()
                stream.writeFully(text(REQUEST), REPLAY_DEADLINE)
                stream.shutdownSend()
                written.complete(stream)
            }
        return Request.Early(options.copy(resumption = resumption), written)
    }

    private class Timed(
        val nanos: Long,
        val reply: String,
        val outcome: QuicResumptionOutcome,
    ) {
        fun ms(): String = "${nanos / 1_000_000} ms"
    }

    /** A new client sends [REQUEST] as [request] says; how long from its start until it has the whole reply. */
    private suspend fun CoroutineScope.timedRequest(
        pair: VirtualPair,
        request: Request,
    ): Timed {
        val client = pair.reconnect(request.options)
        val started = pair.now()
        val connection = client.connection
        val timed = CompletableDeferred<Timed>()
        launch {
            connection.runEarlyData(request.options.resumption, client.offer) { connection.established() }
            val stream =
                when (request) {
                    is Request.Early -> request.stream.await()
                    is Request.Fresh ->
                        connection.openStream().also {
                            it.writeFully(text(REQUEST), REPLAY_DEADLINE)
                            it.shutdownSend()
                        }
                }
            val reply = stream.readText()
            timed.complete(Timed(pair.now() - started, reply, connection.resumption))
        }
        pair.runUntil(within = 10.seconds) { timed.isCompleted }
        return timed.getCompleted()
    }

    private suspend fun QuicByteStream.readText(): String {
        val out = StringBuilder()
        while (true) {
            when (val chunk = read(REPLAY_DEADLINE) { it.readString(it.remaining(), Charset.UTF8) }) {
                is ScopedRead.Data -> out.append(chunk.value)
                ScopedRead.End, ScopedRead.Reset -> return out.toString()
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
        const val RTT_NANOS = 60_000_000L
        const val SLACK_NANOS = 10_000_000L
    }
}
