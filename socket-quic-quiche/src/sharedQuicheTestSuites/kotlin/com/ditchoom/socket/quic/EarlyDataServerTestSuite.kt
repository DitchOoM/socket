@file:OptIn(ExperimentalDatagramApi::class, ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A server that accepts 0-RTT runs its handler on arrival, before the handshake completes, and keeps a
 * replayable request away from a handler that did not ask for it.
 *
 * Each test resumes a session with a request written as 0-RTT while [HandshakeHoldChannel] drops the
 * client's Handshake packets, so the server receives the request but never the client's Finished: its
 * handshake stays incomplete for as long as the test holds it, and nothing the handler does can be
 * explained by confirmation having happened.
 */
abstract class EarlyDataServerTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Build a server on [binding] whose quiche backend is [api]; each platform names its own build function. */
    internal abstract suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
        api: QuicheApi,
    ): SharedQuicheServer

    /** The backend a member wraps. */
    protected abstract fun platformQuicheApi(): QuicheApi

    /** JVM/Android members turn a missing native into a typed skip. */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

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
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                withHeldServer { server, channel ->
                    val seen = Channel<Seen>(Channel.UNLIMITED)
                    val serving =
                        serve(server) {
                            val stateAtStart = quicheDriver().state.value
                            val stream = acceptStream()
                            stream.markReplaySafe()
                            val payload = stream.readToEnd()
                            seen.send(Seen(stateAtStart, stream.origin, payload, quicheDriver().state.value, channel.holding))
                            stream.writeFullyAndShutdownSend(text("ack:$payload"), STREAM_DEADLINE)
                        }
                    try {
                        val ticket = firstConnection(server.port)
                        withTimeout(STEP) { seen.receive() }

                        channel.holdHandshakes()
                        val reply =
                            earlyReconnect(server.port, ticket) { stream ->
                                val reply = stream.readToEnd()
                                // Every Handshake datagram since holdHandshakes() has been dropped, so the server
                                // cannot have completed its handshake. The reply can reach the client before its
                                // own Finished reaches the hold — both leave the client's same flight — so the
                                // drop that proves the hold intercepted it is awaited after, not asserted here.
                                assertTrue(channel.holding, "the reply arrived while the client's Finished was held")
                                withTimeout(STEP) { channel.awaitFirstDrop() }
                                channel.release()
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
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                withHeldServer { server, channel ->
                    val seen = Channel<Seen>(Channel.UNLIMITED)
                    val accepted = Channel<Pair<QuicConnectionState, QuicStreamOrigin>>(Channel.UNLIMITED)
                    val serving =
                        serve(server) {
                            val stateAtStart = quicheDriver().state.value
                            val stream = acceptStream()
                            accepted.send(quicheDriver().state.value to stream.origin)
                            val payload = stream.readToEnd()
                            seen.send(Seen(stateAtStart, stream.origin, payload, quicheDriver().state.value, channel.holding))
                            stream.writeFullyAndShutdownSend(text("ack:$payload"), STREAM_DEADLINE)
                        }
                    try {
                        val ticket = firstConnection(server.port)
                        withTimeout(STEP) { accepted.receive() }
                        withTimeout(STEP) { seen.receive() }

                        channel.holdHandshakes()
                        val reply =
                            earlyReconnect(server.port, ticket) { stream ->
                                val (stateAtAccept, origin) = withTimeout(STEP) { accepted.receive() }
                                assertEquals(
                                    QuicConnectionState.Handshaking,
                                    stateAtAccept,
                                    "the handler had the stream before confirmation",
                                )
                                assertIs<QuicStreamOrigin.Early>(origin)
                                withTimeout(STEP) { channel.awaitFirstDrop() }
                                assertTrue(
                                    seen.isEmpty,
                                    "the handler read an early byte with its handshake incomplete: ${seen.tryReceive()}",
                                )
                                channel.release()
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
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                withHeldServer(serverOptions.copy(idleTimeout = SHORT_IDLE)) { server, channel ->
                    val outcomes = Channel<HandlerOutcome>(Channel.UNLIMITED)
                    val serving =
                        serve(server) {
                            val stateAtStart = quicheDriver().state.value
                            try {
                                val stream = acceptStream()
                                val payload = stream.readToEnd()
                                stream.writeFullyAndShutdownSend(text("ack:$payload"), STREAM_DEADLINE)
                                outcomes.send(HandlerOutcome.Served(stateAtStart, payload))
                            } catch (e: QuicCloseException) {
                                outcomes.trySend(HandlerOutcome.Closed(stateAtStart, e))
                                throw e
                            } catch (e: CancellationException) {
                                // The other typed path: runUntilClosed cancels a handler on a dead connection with
                                // the QuicCloseException as the cause. Which of the two reaches the handler is a
                                // race between its parked read and the close watcher.
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

                        channel.holdHandshakes()
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

                        channel.release()
                        val next =
                            withQuicConnection("127.0.0.1", server.port, clientOptions, timeout = STEP) {
                                openStream().exchange(LATE_PAYLOAD)
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

    private suspend fun withHeldServer(
        options: QuicOptions = serverOptions,
        block: suspend CoroutineScope.(SharedQuicheServer, HandshakeHoldChannel) -> Unit,
    ) {
        val socket =
            UdpSocket.bind(
                "127.0.0.1",
                0,
                receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE,
                bufferFactory = BufferFactory.network(),
            )
        val channel = HandshakeHoldChannel(socket)
        val server = buildServer(QuicPortBinding.Shared(channel), testTlsConfig(), options, platformQuicheApi())
        try {
            coroutineScope { block(server, channel) }
        } finally {
            server.close()
        }
    }

    private fun CoroutineScope.serve(
        server: SharedQuicheServer,
        handler: suspend QuicScope.() -> Unit,
    ): Job = launch { server.connections(handler) }

    private fun QuicScope.quicheDriver(): QuicheDriver = (this as QuicheBackedConnection).quicheDriver

    private suspend fun firstConnection(port: Int): QuicSessionTicket =
        withQuicConnection("127.0.0.1", port, clientOptions, timeout = STEP) {
            assertEquals("ack:$FIRST_PAYLOAD", openStream().exchange(FIRST_PAYLOAD))
            val issued = withTimeout(STEP) { sessionTicket.first { it is QuicSessionTicketState.Issued } }
            assertIs<QuicSessionTicketState.Issued>(issued).ticket
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
        return withQuicConnection("127.0.0.1", port, options, timeout = STEP) {
            assertEquals(QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.Accepted), resumption, "0-RTT must be accepted")
            onStream(withTimeout(STEP) { early.await() })
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

    private companion object {
        const val PROTOCOL = "early-test"
        const val FIRST_PAYLOAD = "first"
        const val EARLY_PAYLOAD = "early-hello"
        const val LATE_PAYLOAD = "late-hello"
        val STREAM_DEADLINE: Duration = 20.seconds
        val STEP: Duration = 20.seconds

        /** The idle timeout that ends the held handshake in the failure test. */
        val SHORT_IDLE: Duration = 2.seconds
    }
}
