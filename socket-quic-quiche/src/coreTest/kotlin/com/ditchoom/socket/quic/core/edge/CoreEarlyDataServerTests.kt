package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.HandshakeHold
import com.ditchoom.socket.quic.HandshakeHoldApi
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicEarlyDataOutcome
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.QuicStreamOrigin
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.demux.loopbackV4
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A server that accepts 0-RTT runs its handler on arrival, before the handshake completes, and keeps a replayable request
 * away from a handler that did not ask for it, on the run-to-completion core's server.
 *
 * Each test resumes a session with a request written as 0-RTT while [HandshakeHoldApi] drops the client's Handshake
 * packets at the server's quiche, so the server receives the request but never the client's Finished: its handshake
 * stays incomplete for as long as the test holds it, and nothing the handler does can be explained by confirmation
 * having happened.
 */
class CoreEarlyDataServerTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    private val clientOptions =
        QuicOptions(
            alpnProtocols = listOf(PROTOCOL),
            verifyPeer = false,
            idleTimeout = 10.seconds,
            migration = MigrationPolicy.Manual,
        )

    private val serverOptions = clientOptions.copy(enableEarlyData = true)

    /** What the server handler saw on one connection. */
    private data class Seen(
        val stateAtStart: QuicConnectionState,
        val origin: QuicStreamOrigin,
        val payload: String,
        val stateAtRead: QuicConnectionState,
        val heldAtRead: Boolean,
    )

    @Test
    fun aHandlerThatMarksAnEarlyStreamReplaySafeAnswersItBeforeTheHandshakeCompletes() =
        coreTest("CoreEarlyDataServerTests.aHandlerThatMarksAnEarlyStreamReplaySafeAnswersItBeforeTheHandshakeCompletes") {
            withTimeout(60.seconds) {
                withHeldServer { server, hold ->
                    val seen = Channel<Seen>(Channel.UNLIMITED)
                    val serving =
                        serve(server) {
                            val stateAtStart = state.value
                            val stream = acceptStream()
                            stream.markReplaySafe()
                            val payload = stream.readToEnd()
                            seen.send(Seen(stateAtStart, stream.origin, payload, state.value, hold.holding))
                            stream.writeFullyAndShutdownSend(text("ack:$payload"), STREAM_DEADLINE)
                        }
                    try {
                        val ticket = firstConnection(server.port)
                        withTimeout(STEP) { seen.receive() }

                        hold.holdHandshakes()
                        val reply =
                            earlyReconnect(server.port, ticket) { stream ->
                                val reply = stream.readToEnd()
                                // Every Handshake datagram since holdHandshakes() has been dropped, so the server
                                // cannot have completed its handshake. The reply can reach the client before its
                                // own Finished reaches the hold: both leave the client's same flight. So the drop
                                // that proves the hold intercepted it is awaited after, not asserted here.
                                assertTrue(hold.holding, "the reply arrived while the client's Finished was held")
                                withTimeout(STEP) { hold.awaitFirstDrop() }
                                hold.release()
                                reply
                            }
                        val early = withTimeout(STEP) { seen.receive() }
                        assertEquals(QuicConnectionState.Handshaking, early.stateAtStart, "the handler starts on arrival. Saw $early")
                        assertIs<QuicStreamOrigin.Early>(early.origin, "the request travelled as 0-RTT. Saw $early")
                        assertEquals(EARLY_PAYLOAD, early.payload)
                        assertEquals(
                            QuicConnectionState.Handshaking,
                            early.stateAtRead,
                            "a stream marked replay-safe is read without waiting for confirmation. Saw $early",
                        )
                        assertEquals("ack:$EARLY_PAYLOAD", reply)
                    } finally {
                        serving.cancel()
                    }
                }
            }
        }

    @Test
    fun aHandlerThatDoesNotOptInReadsNoEarlyByteBeforeTheHandshakeIsConfirmed() =
        coreTest("CoreEarlyDataServerTests.aHandlerThatDoesNotOptInReadsNoEarlyByteBeforeTheHandshakeIsConfirmed") {
            withTimeout(60.seconds) {
                withHeldServer { server, hold ->
                    val seen = Channel<Seen>(Channel.UNLIMITED)
                    val accepted = Channel<Pair<QuicConnectionState, QuicStreamOrigin>>(Channel.UNLIMITED)
                    val serving =
                        serve(server) {
                            val stateAtStart = state.value
                            val stream = acceptStream()
                            accepted.send(state.value to stream.origin)
                            val payload = stream.readToEnd()
                            seen.send(Seen(stateAtStart, stream.origin, payload, state.value, hold.holding))
                            stream.writeFullyAndShutdownSend(text("ack:$payload"), STREAM_DEADLINE)
                        }
                    try {
                        val ticket = firstConnection(server.port)
                        withTimeout(STEP) { accepted.receive() }
                        withTimeout(STEP) { seen.receive() }

                        hold.holdHandshakes()
                        val reply =
                            earlyReconnect(server.port, ticket) { stream ->
                                val (stateAtAccept, origin) = withTimeout(STEP) { accepted.receive() }
                                assertEquals(
                                    QuicConnectionState.Handshaking,
                                    stateAtAccept,
                                    "the handler had the stream before confirmation",
                                )
                                assertIs<QuicStreamOrigin.Early>(origin)
                                withTimeout(STEP) { hold.awaitFirstDrop() }
                                assertTrue(
                                    seen.isEmpty,
                                    "the handler read an early byte with its handshake incomplete: ${seen.tryReceive()}",
                                )
                                hold.release()
                                stream.readToEnd()
                            }
                        val late = withTimeout(STEP) { seen.receive() }
                        assertEquals(QuicConnectionState.Handshaking, late.stateAtStart, "the handler starts on arrival. Saw $late")
                        assertEquals(EARLY_PAYLOAD, late.payload)
                        assertIs<QuicConnectionState.Established>(late.stateAtRead, "the read waited for confirmation. Saw $late")
                        assertTrue(!late.heldAtRead, "the read returned only after the Finished was let through. Saw $late")
                        assertEquals("ack:$EARLY_PAYLOAD", reply)
                    } finally {
                        serving.cancel()
                    }
                }
            }
        }

    @Test
    fun aHandshakeThatFailsAfterTheHandlerStartedClosesItTypedAndTheServerServesTheNext() =
        coreTest("CoreEarlyDataServerTests.aHandshakeThatFailsAfterTheHandlerStartedClosesItTypedAndTheServerServesTheNext") {
            withTimeout(60.seconds) {
                withHeldServer(serverOptions.copy(idleTimeout = SHORT_IDLE)) { server, hold ->
                    val outcomes = Channel<HandlerOutcome>(Channel.UNLIMITED)
                    val serving =
                        serve(server) {
                            val stateAtStart = state.value
                            try {
                                val stream = acceptStream()
                                val payload = stream.readToEnd()
                                stream.writeFullyAndShutdownSend(text("ack:$payload"), STREAM_DEADLINE)
                                outcomes.send(HandlerOutcome.Served(stateAtStart, payload))
                            } catch (e: QuicCloseException) {
                                outcomes.trySend(HandlerOutcome.Closed(stateAtStart, e))
                                throw e
                            } catch (e: CancellationException) {
                                // The connection's end may cancel a parked handler with the QuicCloseException as the
                                // cause. Which of the two reaches the handler is a race between its parked read and
                                // the end.
                                val close = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<QuicCloseException>().firstOrNull()
                                outcomes.trySend(
                                    close?.let { HandlerOutcome.Closed(stateAtStart, it) } ?: HandlerOutcome.Cancelled(stateAtStart, e),
                                )
                                throw e
                            }
                        }
                    try {
                        val ticket = firstConnection(server.port)
                        assertIs<HandlerOutcome.Served>(withTimeout(STEP) { outcomes.receive() })

                        hold.holdHandshakes()
                        coroutineScope {
                            val client = CompletableDeferred<Throwable>()
                            val abandoned =
                                launch {
                                    try {
                                        earlyReconnect(server.port, ticket) { stream -> stream.readToEnd() }
                                    } catch (e: Exception) {
                                        // The client idles out too (the server's idle timeout bounds both ends);
                                        // what it saw is kept only to explain a stall.
                                        client.complete(e)
                                    }
                                }
                            val failed =
                                withTimeoutOrNull(STEP) { outcomes.receive() }
                                    ?: fail("no handler outcome; client ended with ${client.takeIf { it.isCompleted }?.getCompleted()}")
                            abandoned.cancel()
                            assertIs<HandlerOutcome.Closed>(failed, "the handler must see its connection end, typed. Saw $failed")
                            assertEquals(
                                QuicConnectionState.Handshaking,
                                failed.stateAtStart,
                                "the handler started before the handshake failed",
                            )
                        }

                        hold.release()
                        val client = connectCore(server.port, loops = loops, options = clientOptions)
                        val next =
                            try {
                                client.connection.openStream().exchange(LATE_PAYLOAD)
                            } finally {
                                client.connection.close(QuicError.NoError)
                            }
                        assertEquals("ack:$LATE_PAYLOAD", next, "the server keeps serving after one connection's handshake failed")
                    } finally {
                        serving.cancel()
                    }
                }
            }
        }

    private sealed interface HandlerOutcome {
        data class Served(
            val stateAtStart: QuicConnectionState,
            val payload: String,
        ) : HandlerOutcome

        data class Closed(
            val stateAtStart: QuicConnectionState,
            val error: QuicCloseException,
        ) : HandlerOutcome

        data class Cancelled(
            val stateAtStart: QuicConnectionState,
            val cause: CancellationException,
        ) : HandlerOutcome
    }

    // ---- harness --------------------------------------------------------------------------------

    /** A core server whose quiche drops inbound Handshake datagrams while [HandshakeHold.holding]. */
    private suspend fun withHeldServer(
        options: QuicOptions = serverOptions,
        block: suspend CoroutineScope.(CoreServer, HandshakeHold) -> Unit,
    ) {
        val api = HandshakeHoldApi(platformQuicheApi())
        val server =
            coreServe(
                mismatch = MismatchResponse.Fail,
                bind = loopbackV4(),
                tls = platformTlsConfig(),
                options = options,
                factory = BufferFactory.network(),
                api = api,
                readPolicy = ReadPolicy.Bounded(DEADLINE),
                writePolicy = WritePolicy.Bounded(DEADLINE),
            )
        try {
            coroutineScope { block(server, api) }
        } finally {
            server.close()
        }
    }

    /** Runs [handler] for every connection [server] starts: each on its own, the connection closing when it returns or fails. */
    private fun CoroutineScope.serve(
        server: CoreServer,
        handler: suspend QuicScope.() -> Unit,
    ): Job =
        launch {
            val serving = this
            server.connections().collect { connection ->
                serving.launch {
                    try {
                        connection.handler()
                    } catch (ended: Exception) {
                        // The test asserts on what its handler saw; the handler's end is the connection's.
                    } finally {
                        connection.closeNow(QuicError.NoError)
                    }
                }
            }
        }

    private suspend fun firstConnection(port: Int): QuicSessionTicket {
        val client = connectCore(port, loops = loops, options = clientOptions)
        try {
            assertEquals("ack:$FIRST_PAYLOAD", client.connection.openStream().exchange(FIRST_PAYLOAD))
            val issued = withTimeout(STEP) { client.connection.sessionTicket.first { it is QuicSessionTicketState.Issued } }
            return assertIs<QuicSessionTicketState.Issued>(issued).ticket
        } finally {
            client.connection.close(QuicError.NoError)
        }
    }

    /** Resume with [ticket], writing [EARLY_PAYLOAD] as 0-RTT, and hand its stream to [onStream]. */
    private suspend fun <R> earlyReconnect(
        port: Int,
        ticket: QuicSessionTicket,
        onStream: suspend (QuicByteStream) -> R,
    ): R {
        val early = CompletableDeferred<QuicByteStream>()
        val options =
            clientOptions.copy(
                resumption =
                    QuicResumption.ResumeWithEarlyData(ticket) {
                        val stream = openStream()
                        stream.writeFullyAndShutdownSend(text(EARLY_PAYLOAD), STREAM_DEADLINE)
                        early.complete(stream)
                    },
            )
        val client = connectCore(port, loops = loops, options = options)
        try {
            assertEquals(
                QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.Accepted),
                client.connection.resumption,
                "0-RTT must be accepted",
            )
            return onStream(withTimeout(STEP) { early.await() })
        } finally {
            client.connection.close(QuicError.NoError)
        }
    }

    private suspend fun QuicByteStream.exchange(payload: String): String {
        writeFully(text(payload), STREAM_DEADLINE)
        shutdownSend()
        return readToEnd()
    }

    private suspend fun QuicByteStream.readToEnd(): String {
        val sb = StringBuilder()
        while (true) {
            when (val chunk = read(STREAM_DEADLINE) { it.readString(it.remaining(), Charset.UTF8) }) {
                is ScopedReadResult.Data -> sb.append(chunk.value)
                ScopedReadResult.End, ScopedReadResult.Reset -> return sb.toString()
            }
        }
    }

    private fun text(payload: String): ReadBuffer {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        return out
    }

    private companion object {
        const val PROTOCOL = "early-test"
        const val FIRST_PAYLOAD = "first"
        const val EARLY_PAYLOAD = "early-hello"
        const val LATE_PAYLOAD = "late-hello"
        val DEADLINE: Duration = 20.seconds
        val STREAM_DEADLINE: Duration = 20.seconds
        val STEP: Duration = 20.seconds

        /** The idle timeout that ends the held handshake in the failure test. */
        val SHORT_IDLE: Duration = 2.seconds
    }
}
