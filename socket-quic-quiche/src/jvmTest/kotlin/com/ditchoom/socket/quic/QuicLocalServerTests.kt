package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class QuicLocalServerTests {
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    /**
     * The last step each peer reached, so a stalled exchange names *where* it stalled.
     *
     * The tests below drive a client and a server handler from two `launch`ed coroutines and wait on a
     * [CompletableDeferred] between them. Some of the calls those peers make are unbounded —
     * `connections`, `acceptStream`, `openStream` — so a stall there throws nothing at all: the deferred
     * is simply never completed and the only evidence is the awaiting side timing out, over a stack that
     * names the await and nothing else. Recording the step before each call is what turns that into a
     * located failure. Written from the peer coroutines, read from the test coroutine on timeout, hence
     * `@Volatile`.
     */
    private class Steps {
        @Volatile
        var client: String = "not started"

        @Volatile
        var server: String = "not started"

        override fun toString() = "client stalled at '$client', server at '$server'"
    }

    /**
     * Run a launched peer so that a failure inside it reaches [result] instead of vanishing.
     *
     * `withTimeout` raises [TimeoutCancellationException], which **is** a `CancellationException`: thrown
     * inside `launch` it cancels that one coroutine and is never reported anywhere. So a peer whose
     * bounded read or write times out dies silently, [result] is never completed, and the awaiting side
     * reports a bare timeout carrying none of that. Completing [result] exceptionally makes the peer's
     * own exception the reported failure, with the step it died on in the message.
     *
     * Catching [Throwable] deliberately includes the cancellation the test's own `finally` sends after a
     * *successful* exchange: [CompletableDeferred.completeExceptionally] on an already-completed deferred
     * is a no-op, so the happy path is unaffected.
     */
    private suspend fun <T> reporting(
        result: CompletableDeferred<T>,
        peer: String,
        stepOf: () -> String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            result.completeExceptionally(IllegalStateException("$peer failed at '${stepOf()}'", t))
        }
    }

    @Test
    fun serverAcceptsConnection() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(QuicLocalServerTests::class) {
                withTimeout(15.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val handlerRan = CompletableDeferred<Unit>()

                        // Handler-immediate pattern; delay(N) in handler deadlocks driver
                        // shutdown on CI (see QuicServerTestSuite.serverAcceptsConnection).
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    handlerRan.complete(Unit)
                                }
                            }

                        try {
                            withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                // Empty.
                            }
                            withTimeout(10.seconds) { handlerRan.await() }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    @Test
    fun echoSingleStream() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(QuicLocalServerTests::class) {
                withTimeout(15.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val echoResult = CompletableDeferred<String>()
                        val steps = Steps()

                        val serverJob =
                            launch(Dispatchers.IO) {
                                reporting(echoResult, "server", { steps.server }) {
                                    steps.server = "connections"
                                    connections {
                                        steps.server = "acceptStream"
                                        val stream = acceptStream()
                                        steps.server = "read"
                                        // Scoped read (#538): echo zero-copy inside the block, and the
                                        // read buffer goes back to the driver's pool on the way out.
                                        stream.read(5.seconds) {
                                            steps.server = "write"
                                            stream.write(it, 5.seconds)
                                        }
                                        steps.server = "close"
                                        stream.close()
                                        steps.server = "echoed"
                                    }
                                }
                            }

                        val clientJob =
                            launch(Dispatchers.IO) {
                                reporting(echoResult, "client", { steps.client }) {
                                    steps.client = "connect"
                                    withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                        steps.client = "openStream"
                                        val stream = openStream()
                                        val sendBuf = BufferFactory.Default.allocate(11)
                                        sendBuf.writeString("hello quic!", Charset.UTF8)
                                        sendBuf.resetForRead()
                                        steps.client = "write"
                                        stream.write(sendBuf, 5.seconds)

                                        steps.client = "read"
                                        val response = stream.read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
                                        if (response is ScopedRead.Data) {
                                            echoResult.complete(response.value)
                                        } else {
                                            echoResult.complete("no_data")
                                        }
                                        steps.client = "close"
                                        stream.close()
                                    }
                                }
                            }

                        try {
                            val result =
                                try {
                                    withTimeout(10.seconds) { echoResult.await() }
                                } catch (e: TimeoutCancellationException) {
                                    // Neither peer reported anything, so both are parked in an unbounded
                                    // call — the steps are the only evidence of which one.
                                    throw AssertionError("echo never completed: $steps", e)
                                }
                            assertEquals("hello quic!", result)
                        } finally {
                            clientJob.cancel()
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    /**
     * #318, against real quiche: the peer answers a half-closed request, FINs the stream and closes
     * the connection, all before the application reads. Those bytes were accepted and acknowledged by
     * our transport, so the connection ending does not un-receive them (RFC 9000 §10.2) — the read
     * must still deliver `ping`.
     *
     * The emulator lane hit this by losing a scheduler race (a reader whose wakeup arrived after the
     * teardown, reporting `no_data:End`); here the losing interleaving is *forced*: the client simply
     * does not read until well past the connection's draining period, so the read provably runs after
     * its own driver tore the connection down and freed the quiche connection. Without the teardown
     * drain in QuicheDriver.transitionToClosed this returns `no_data:End` every time — the same
     * verdict, with the peer's reply discarded — which is the end-to-end mutation proof that the
     * drained bytes are real quiche's and not a stub's.
     *
     * The server runs with [QuicCloseLinger.Immediate] on purpose: the graceful close added for #321
     * would otherwise hold the CONNECTION_CLOSE until this client is done, and the teardown race this
     * test exists to reproduce would never happen. The two behaviours are complementary — #321 stops
     * the *sender* closing over undelivered bytes, #318 stops the *receiver* discarding delivered ones
     * when a close does arrive — so this keeps asserting the receive half in isolation.
     */
    @Test
    fun replyBufferedWhenPeerClosesIsStillDelivered() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(QuicLocalServerTests::class) {
                withTimeout(30.seconds) {
                    val testQuicOptions = testQuicOptions.copy(closeLinger = QuicCloseLinger.Immediate)
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val echoResult = CompletableDeferred<String>()
                        val steps = Steps()

                        val serverJob =
                            launch(Dispatchers.IO) {
                                reporting(echoResult, "server", { steps.server }) {
                                    steps.server = "connections"
                                    connections {
                                        steps.server = "acceptStream"
                                        val stream = acceptStream()
                                        // Drain the request to the peer's FIN, echo it back, FIN our side.
                                        val received = StringBuilder()
                                        while (true) {
                                            steps.server = "read(${received.length})"
                                            val r = stream.read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
                                            if (r !is ScopedRead.Data) break
                                            received.append(r.value)
                                        }
                                        val reply = BufferFactory.Default.allocate(received.length)
                                        reply.writeString(received.toString(), Charset.UTF8)
                                        reply.resetForRead()
                                        steps.server = "write"
                                        stream.write(reply, 5.seconds)
                                        steps.server = "close"
                                        stream.close()
                                        steps.server = "echoed"
                                        // Returning from the handler closes the connection: CONNECTION_CLOSE
                                        // reaches the client while its reply is still unread.
                                    }
                                }
                            }

                        val clientJob =
                            launch(Dispatchers.IO) {
                                reporting(echoResult, "client", { steps.client }) {
                                    steps.client = "connect"
                                    withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                        steps.client = "openStream"
                                        val stream = openStream()
                                        val sendBuf = BufferFactory.Default.allocate(4)
                                        sendBuf.writeString("ping", Charset.UTF8)
                                        sendBuf.resetForRead()
                                        steps.client = "write"
                                        stream.write(sendBuf, 5.seconds)
                                        steps.client = "shutdownSend"
                                        stream.shutdownSend()

                                        // Outlast the draining period (3 × PTO, tens of ms) by a wide margin
                                        // so the reply, the FIN and the CONNECTION_CLOSE have all been
                                        // processed and the driver has torn the connection down before the
                                        // first read is even issued.
                                        steps.client = "draining delay"
                                        delay(2.seconds)

                                        steps.client = "read"
                                        val response = stream.read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
                                        if (response is ScopedRead.Data) {
                                            echoResult.complete(response.value)
                                        } else {
                                            echoResult.complete("no_data:${response::class.simpleName}")
                                        }
                                        steps.client = "close"
                                        stream.close()
                                    }
                                }
                            }

                        try {
                            val result =
                                try {
                                    withTimeout(15.seconds) { echoResult.await() }
                                } catch (e: TimeoutCancellationException) {
                                    throw AssertionError("reply never arrived: $steps", e)
                                }
                            assertEquals("ping", result)
                        } finally {
                            clientJob.cancel()
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    /**
     * #321, against real quiche: the reply's first datagrams are **lost in flight**, and the server
     * closes the connection as soon as its handler returns.
     *
     * RFC 9000 §10.2 makes CONNECTION_CLOSE terminal for the sender: once it is sent, quiche emits
     * nothing else — including the retransmission the dropped datagrams need. So a server that closes
     * the instant its handler returns has made its last reply unreliable; the peer sees the connection
     * end and the bytes simply never arrive. Nothing about that is visible from either application:
     * the write succeeded, the close succeeded, the data is gone.
     *
     * The loss is deterministic, not probabilistic: an in-process UDP proxy drops the first
     * [DROPPED_REPLY_DATAGRAMS] server→client datagrams after [ImpairingProxy.arm], and the client arms
     * it only after a warm-up echo has round-tripped — so the handshake tail is flushed and acked
     * beforehand and the drop window covers the reply burst itself. The reply is [REPLY_SIZE] bytes
     * (≈7 datagrams), so the dropped prefix cannot be reassembled from what gets through: without a
     * retransmission the client has a hole at offset 0 and can deliver nothing at all.
     *
     * With the graceful close, the connection stays up while the handler's coroutine waits, quiche's
     * loss timers fire on the driver loop, the lost range is retransmitted, and the client reads the
     * whole payload before the server closes. Flipping this server to [QuicCloseLinger.Immediate] —
     * exactly the pre-fix behaviour, asserted by [replyLostInFlightIsTruncatedByAnImmediateClose] — the
     * client gets nothing.
     */
    @Test
    fun replyLostInFlightIsRetransmittedBeforeTheServerCloses() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(QuicLocalServerTests::class) {
                val received = replyUnderDroppedDatagrams(QuicCloseLinger.Default, readBudget = 20.seconds)
                assertEquals(
                    replyPayload.length,
                    received.length,
                    "the reply was truncated: the server closed before quiche could retransmit the dropped datagrams",
                )
                assertEquals(replyPayload, received, "the retransmitted reply must be byte-identical")
            }
        }

    /**
     * Anti-vacuous guard for [replyLostInFlightIsRetransmittedBeforeTheServerCloses], and the
     * pre-#321 behaviour written down: with [QuicCloseLinger.Immediate] the same scenario loses the
     * reply outright. If the proxy ever stopped dropping the reply burst — the one way the positive
     * test could pass without the fix doing anything — this would start passing the payload through
     * and fail here instead.
     */
    @Test
    fun replyLostInFlightIsTruncatedByAnImmediateClose() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(QuicLocalServerTests::class) {
                val received = replyUnderDroppedDatagrams(QuicCloseLinger.Immediate, readBudget = 4.seconds)
                assertTrue(
                    received.length < replyPayload.length,
                    "closing immediately after the handler cannot deliver a dropped reply — got ${received.length} bytes",
                )
            }
        }

    /**
     * Request/reply against a real quiche server whose first [DROPPED_REPLY_DATAGRAMS] reply datagrams
     * are dropped by an in-process proxy, returning whatever the client managed to read within
     * [readBudget]. The server closes the connection as soon as its handler returns; [closeLinger]
     * decides whether that close waits for the peer.
     */
    private suspend fun replyUnderDroppedDatagrams(
        closeLinger: QuicCloseLinger,
        readBudget: Duration,
    ): String {
        val options = testQuicOptions.copy(closeLinger = closeLinger)
        var received = ""
        withTimeout(60.seconds) {
            withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
                val serverJob =
                    launch(Dispatchers.IO) {
                        connections {
                            val stream = acceptStream()
                            // Warm-up leg: echo the token back, so the client can prove the path is up
                            // (and the handshake tail acked) before it arms the impairment.
                            stream.read(10.seconds) { stream.write(it, 10.seconds) }
                            // Request leg: drain to the peer's FIN.
                            while (true) {
                                if (stream.read(10.seconds) { it.remaining() } !is ScopedRead.Data) break
                            }
                            // The reply this test is about: written, FINed, and then the handler returns,
                            // which closes the connection.
                            val reply = BufferFactory.Default.allocate(replyPayload.length)
                            reply.writeString(replyPayload, Charset.UTF8)
                            reply.resetForRead()
                            stream.write(reply, 10.seconds)
                            stream.close()
                        }
                    }

                val proxy =
                    DatagramChannelImpairingProxy(port) { direction, index ->
                        if (direction == ImpairDirection.ServerToClient && index < DROPPED_REPLY_DATAGRAMS) {
                            ImpairAction.Drop
                        } else {
                            ImpairAction.Forward
                        }
                    }
                val done = CompletableDeferred<String>()
                val clientJob =
                    launch(Dispatchers.IO) {
                        try {
                            withQuicConnection("127.0.0.1", proxy.proxyPort, options, timeout = 15.seconds) {
                                val stream = openStream()
                                writeAscii(stream, WARMUP)
                                assertEquals(WARMUP, readUpTo(stream, WARMUP.length, 10.seconds), "warm-up echo failed before arming")
                                proxy.arm() // from here, the server's next datagrams are the ones dropped
                                writeAscii(stream, "request")
                                stream.shutdownSend()
                                done.complete(readUpTo(stream, replyPayload.length, readBudget))
                                stream.close()
                            }
                        } catch (t: Throwable) {
                            done.completeExceptionally(t)
                        }
                    }

                try {
                    received = done.await()
                    assertTrue(proxy.droppedCount > 0, "no datagram was dropped — the impairment never fired")
                } finally {
                    clientJob.cancel()
                    serverJob.cancel()
                    proxy.close()
                }
            }
        }
        return received
    }

    private suspend fun writeAscii(
        stream: QuicByteStream,
        text: String,
    ) {
        val buf = BufferFactory.Default.allocate(text.length)
        buf.writeString(text, Charset.UTF8)
        buf.resetForRead()
        stream.write(buf, 10.seconds)
    }

    /**
     * Accumulate stream reads until [total] characters have arrived, the stream ends, or a read runs
     * out of time — returning what did arrive. A read timeout is a *result* here, not a failure: the
     * unfixed server simply never sends the rest, and the assertion should be about the missing bytes
     * rather than about a stack trace.
     */
    private suspend fun readUpTo(
        stream: QuicByteStream,
        total: Int,
        timeout: Duration,
    ): String {
        val sb = StringBuilder(total)
        while (sb.length < total) {
            val r =
                try {
                    stream.read(timeout) { it.readString(it.remaining(), Charset.UTF8) }
                } catch (_: TimeoutCancellationException) {
                    break
                }
            if (r !is ScopedRead.Data) break
            sb.append(r.value)
        }
        return sb.toString()
    }

    private companion object {
        /** Warm-up token: proves the path (and flushes the handshake tail) before the impairment arms. */
        const val WARMUP = "warmup"

        /**
         * Post-arm server→client datagrams to drop — the reply's whole first transmission (≈7
         * datagrams at [REPLY_SIZE]) plus a PTO round, so recovery provably comes from a
         * retransmission that happens *after* the handler returned.
         */
        const val DROPPED_REPLY_DATAGRAMS = 10

        /** ~8 KB ⇒ several datagrams, so a dropped prefix leaves a hole nothing else can fill. */
        const val REPLY_SIZE = 8 * 1024

        /** Deterministic single-byte-UTF8 payload, so a chunk boundary never splits a codepoint. */
        val replyPayload =
            buildString(REPLY_SIZE) {
                for (i in 0 until REPLY_SIZE) append('A' + (i % 26))
            }
    }
}
