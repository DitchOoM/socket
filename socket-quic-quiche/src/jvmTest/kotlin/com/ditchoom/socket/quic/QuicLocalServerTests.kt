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
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
     * Run [body] — a two-peer exchange built on [diag] — and, on ANY failure, replace the bare
     * exception with one that carries the located failure, both ends' traces and the thread
     * inventory (see [LoopbackPeerDiagnostics] for why each is needed). Printed AND folded into the
     * exception: the job log shows the exception and the JNI→FFM re-run clobbers the test XML, so
     * only the exception message reliably survives CI.
     *
     * Thrown as an `AssertionError` on purpose — a `TimeoutCancellationException` IS a
     * `CancellationException`, and at the test boundary a cancellation is a failure.
     */
    private suspend fun diagnosed(
        diag: LoopbackPeerDiagnostics,
        body: suspend () -> Unit,
    ) {
        try {
            body()
        } catch (t: Throwable) {
            val report = diag.report(t)
            println(report)
            throw AssertionError(report, t)
        }
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
        stepOf: () -> PeerStep,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            result.completeExceptionally(IllegalStateException("$peer failed at ${stepOf()}", t))
        }
    }

    @Test
    fun serverAcceptsConnection() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(QuicLocalServerTests::class) {
                val diag = LoopbackPeerDiagnostics()
                diagnosed(diag) {
                    withTimeout(15.seconds) {
                        withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = diag.serverOptions(testQuicOptions)) {
                            val handlerRan = CompletableDeferred<Unit>()

                            // Handler-immediate pattern; delay(N) in handler deadlocks driver
                            // shutdown on CI (see QuicServerTestSuite.serverAcceptsConnection).
                            diag.serverStep = PeerStep.Reached("connections")
                            val serverJob =
                                launch(Dispatchers.IO) {
                                    connections {
                                        diag.serverStep = PeerStep.Reached("handler ran")
                                        handlerRan.complete(Unit)
                                    }
                                }

                            try {
                                diag.clientStep = PeerStep.Reached("connect")
                                withQuicConnection("localhost", port, diag.clientOptions(testQuicOptions), timeout = 10.seconds) {
                                    diag.clientStep = PeerStep.Reached("connected")
                                }
                                diag.clientStep = PeerStep.Reached("awaiting the server handler")
                                withTimeout(10.seconds) { handlerRan.await() }
                            } finally {
                                serverJob.cancel()
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun echoSingleStream() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(QuicLocalServerTests::class) {
                val diag = LoopbackPeerDiagnostics()
                diagnosed(diag) {
                    withTimeout(15.seconds) {
                        withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = diag.serverOptions(testQuicOptions)) {
                            val echoResult = CompletableDeferred<String>()

                            val serverJob =
                                launch(Dispatchers.IO) {
                                    reporting(echoResult, "server", { diag.serverStep }) {
                                        diag.serverStep = PeerStep.Reached("connections")
                                        connections {
                                            diag.serverStep = PeerStep.Reached("acceptStream")
                                            val stream = acceptStream()
                                            diag.serverStep = PeerStep.Reached("read")
                                            // Scoped read (#538): echo zero-copy inside the block, and the
                                            // read buffer goes back to the driver's pool on the way out.
                                            stream.read(5.seconds) {
                                                diag.serverStep = PeerStep.Reached("write")
                                                stream.write(it, 5.seconds)
                                            }
                                            diag.serverStep = PeerStep.Reached("close")
                                            stream.close()
                                            diag.serverStep = PeerStep.Reached("echoed")
                                        }
                                    }
                                }

                            val clientJob =
                                launch(Dispatchers.IO) {
                                    reporting(echoResult, "client", { diag.clientStep }) {
                                        diag.clientStep = PeerStep.Reached("connect")
                                        withQuicConnection("localhost", port, diag.clientOptions(testQuicOptions), timeout = 10.seconds) {
                                            diag.clientStep = PeerStep.Reached("openStream")
                                            val stream = openStream()
                                            val sendBuf = BufferFactory.Default.allocate(11)
                                            sendBuf.writeString("hello quic!", Charset.UTF8)
                                            sendBuf.resetForRead()
                                            diag.clientStep = PeerStep.Reached("write")
                                            stream.write(sendBuf, 5.seconds)

                                            diag.clientStep = PeerStep.Reached("read")
                                            val response = stream.read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
                                            if (response is ScopedRead.Data) {
                                                echoResult.complete(response.value)
                                            } else {
                                                echoResult.complete("no_data")
                                            }
                                            diag.clientStep = PeerStep.Reached("close")
                                            stream.close()
                                        }
                                    }
                                }

                            try {
                                val result =
                                    try {
                                        withTimeout(10.seconds) { echoResult.await() }
                                    } catch (e: TimeoutCancellationException) {
                                        // Neither peer reported anything, so both are parked in an
                                        // unbounded call — the steps are the only evidence of which one.
                                        throw AssertionError(
                                            "echo never completed: client stalled at ${diag.clientStep}, server at ${diag.serverStep}",
                                            e,
                                        )
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
                val diag = LoopbackPeerDiagnostics()
                diagnosed(diag) {
                    withTimeout(30.seconds) {
                        val testQuicOptions = testQuicOptions.copy(closeLinger = QuicCloseLinger.Immediate)
                        withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = diag.serverOptions(testQuicOptions)) {
                            val echoResult = CompletableDeferred<String>()

                            val serverJob =
                                launch(Dispatchers.IO) {
                                    reporting(echoResult, "server", { diag.serverStep }) {
                                        diag.serverStep = PeerStep.Reached("connections")
                                        connections {
                                            diag.serverStep = PeerStep.Reached("acceptStream")
                                            val stream = acceptStream()
                                            // Drain the request to the peer's FIN, echo it back, FIN our side.
                                            val received = StringBuilder()
                                            while (true) {
                                                diag.serverStep = PeerStep.Reached("read(${received.length})")
                                                val r = stream.read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
                                                if (r !is ScopedRead.Data) break
                                                received.append(r.value)
                                            }
                                            val reply = BufferFactory.Default.allocate(received.length)
                                            reply.writeString(received.toString(), Charset.UTF8)
                                            reply.resetForRead()
                                            diag.serverStep = PeerStep.Reached("write")
                                            stream.write(reply, 5.seconds)
                                            diag.serverStep = PeerStep.Reached("close")
                                            stream.close()
                                            diag.serverStep = PeerStep.Reached("echoed")
                                            // Returning from the handler closes the connection:
                                            // CONNECTION_CLOSE reaches the client while its reply is still
                                            // unread.
                                        }
                                    }
                                }

                            val clientJob =
                                launch(Dispatchers.IO) {
                                    reporting(echoResult, "client", { diag.clientStep }) {
                                        diag.clientStep = PeerStep.Reached("connect")
                                        withQuicConnection("localhost", port, diag.clientOptions(testQuicOptions), timeout = 10.seconds) {
                                            diag.clientStep = PeerStep.Reached("openStream")
                                            val stream = openStream()
                                            val sendBuf = BufferFactory.Default.allocate(4)
                                            sendBuf.writeString("ping", Charset.UTF8)
                                            sendBuf.resetForRead()
                                            diag.clientStep = PeerStep.Reached("write")
                                            stream.write(sendBuf, 5.seconds)
                                            diag.clientStep = PeerStep.Reached("shutdownSend")
                                            stream.shutdownSend()

                                            // Outlast the draining period (3 × PTO, tens of ms) by a wide
                                            // margin so the reply, the FIN and the CONNECTION_CLOSE have all
                                            // been processed and the driver has torn the connection down
                                            // before the first read is even issued.
                                            diag.clientStep = PeerStep.Reached("draining delay")
                                            delay(2.seconds)

                                            diag.clientStep = PeerStep.Reached("read")
                                            val response = stream.read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
                                            if (response is ScopedRead.Data) {
                                                echoResult.complete(response.value)
                                            } else {
                                                echoResult.complete("no_data:${response::class.simpleName}")
                                            }
                                            diag.clientStep = PeerStep.Reached("close")
                                            stream.close()
                                        }
                                    }
                                }

                            try {
                                val result =
                                    try {
                                        withTimeout(15.seconds) { echoResult.await() }
                                    } catch (e: TimeoutCancellationException) {
                                        throw AssertionError(
                                            "reply never arrived: client stalled at ${diag.clientStep}, server at ${diag.serverStep}",
                                            e,
                                        )
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
                val received =
                    replyUnderDroppedDatagrams(QuicCloseLinger.Default, readBudget = 20.seconds, ReplyImpairment.DropFirstDatagrams)
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
     * pre-#321 behaviour written down: with [QuicCloseLinger.Immediate] the reply is lost outright, and
     * the client's read reports the server's CONNECTION_CLOSE as the typed [QuicCloseException]. The
     * stream's FIN never arrived, so it is not an end-of-stream. If the proxy ever stopped taking the
     * reply away (the one way the positive test could pass without the fix doing anything), the client
     * would read the whole reply and this would fail.
     *
     * The reply is taken away by [ReplyImpairment.BlackoutUntilServerQuiet], not by the positive test's
     * count window. A count window decides nothing here: whether an immediate close truncates the reply
     * depends on whether any reply datagram reaches the client before the server's close goes out. One
     * that does gets ACKed, quiche marks everything before it lost, and the server retransmits the whole
     * reply within a round trip, unless its close went out first. The old ten-datagram window ended
     * exactly at the server's close on a macOS loopback, with nothing to spare; one datagram shorter, the
     * client read the entire reply, which is what CI reported twice ("completed successfully with the
     * result: <ABC…>"). The blackout holds every server datagram until the server has gone quiet after
     * its handler returned, so how fast the server closes no longer matters.
     */
    @Test
    fun replyLostInFlightIsTruncatedByAnImmediateClose() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(QuicLocalServerTests::class) {
                var readEnded: ReadEnding? = null
                val outcome =
                    runCatching {
                        replyUnderDroppedDatagrams(
                            QuicCloseLinger.Immediate,
                            readBudget = 4.seconds,
                            ReplyImpairment.BlackoutUntilServerQuiet,
                            onReadEnded = { readEnded = it },
                        )
                    }
                val error = outcome.exceptionOrNull()
                if (error != null && error !is QuicCloseException) throw error
                // The client's read must not have ended on its own: a read that returned (End, Reset, a
                // timeout, or the whole reply) means the close was not what it reported, even when the
                // connection's block then ended with a QuicCloseException.
                val ending = readEnded
                if (error == null || ending != null) {
                    fail(
                        "closing immediately after the handler cannot deliver a reply the client never received, and the " +
                            "client's read must report that close as a QuicCloseException. Instead the read ended with " +
                            "$ending after ${outcome.getOrNull()?.length ?: "?"} of ${replyPayload.length} bytes " +
                            "(block outcome: ${error ?: "returned normally"}): " +
                            when (ending) {
                                ReadEnding.AllArrived ->
                                    "reply data reached the client although the proxy delivered only the server's last datagram"
                                ReadEnding.End -> "a stream with no FIN read as the peer's clean end-of-stream"
                                ReadEnding.Reset -> "a stream the peer never reset read as reset"
                                ReadEnding.TimedOut, null -> "the close never reached the read"
                            },
                    )
                }
            }
        }

    /** How [replyUnderDroppedDatagrams] takes the reply away from the client. */
    private enum class ReplyImpairment {
        /**
         * Drop the first [DROPPED_REPLY_DATAGRAMS] server→client datagrams after arming, then forward. A
         * server that lingers recovers the reply by retransmitting it.
         */
        DropFirstDatagrams,

        /**
         * Hold every server→client datagram after arming. Once the server's handler has returned and the
         * server has gone quiet, deliver only the last one, its CONNECTION_CLOSE. See [ReplyBlackoutProxy].
         */
        BlackoutUntilServerQuiet,
    }

    /**
     * Request/reply against a real quiche server whose reply an in-process proxy takes away from the
     * client as [impairment] says, returning whatever the client managed to read within [readBudget].
     * [onReadEnded] hears how the reply's read ended, unless it threw.
     * The server closes the connection as soon as its handler returns; [closeLinger] decides whether
     * that close waits for the peer.
     */
    private suspend fun replyUnderDroppedDatagrams(
        closeLinger: QuicCloseLinger,
        readBudget: Duration,
        impairment: ReplyImpairment,
        onReadEnded: (ReadEnding) -> Unit = {},
    ): String {
        val options = testQuicOptions.copy(closeLinger = closeLinger)
        var received = ""
        withTimeout(60.seconds) {
            withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = options) {
                val proxy: ImpairingProxy =
                    when (impairment) {
                        ReplyImpairment.DropFirstDatagrams ->
                            DatagramChannelImpairingProxy(port) { direction, index ->
                                if (direction == ImpairDirection.ServerToClient && index < DROPPED_REPLY_DATAGRAMS) {
                                    ImpairAction.Drop
                                } else {
                                    ImpairAction.Forward
                                }
                            }
                        ReplyImpairment.BlackoutUntilServerQuiet -> ReplyBlackoutProxy(port, quiet = SERVER_QUIET)
                    }
                val serverJob =
                    launch(Dispatchers.IO) {
                        connections {
                            try {
                                replyHandler()
                            } finally {
                                (proxy as? ReplyBlackoutProxy)?.serverHandlerReturned()
                            }
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
                                done.complete(readUpTo(stream, replyPayload.length, readBudget, onReadEnded))
                                stream.close()
                            }
                        } catch (t: Throwable) {
                            done.completeExceptionally(t)
                        }
                    }

                try {
                    // Settled before the drop check so a typed close still gets the anti-vacuous assert.
                    val outcome = runCatching { done.await() }
                    assertTrue(proxy.droppedCount > 0, "no datagram was dropped — the impairment never fired")
                    received = outcome.getOrThrow()
                } finally {
                    clientJob.cancel()
                    serverJob.cancel()
                    proxy.close()
                }
            }
        }
        return received
    }

    /**
     * The server side of [replyUnderDroppedDatagrams]. Warm-up leg: echo the token back, so the client
     * can prove the path is up (and the handshake tail acked) before it arms the impairment. Request
     * leg: drain to the peer's FIN. Then the reply this test is about: written, FINed, and the handler
     * returns, which closes the connection.
     */
    private suspend fun QuicScope.replyHandler() {
        val stream = acceptStream()
        stream.read(10.seconds) { stream.write(it, 10.seconds) }
        while (true) {
            if (stream.read(10.seconds) { it.remaining() } !is ScopedRead.Data) break
        }
        val reply = BufferFactory.Default.allocate(replyPayload.length)
        reply.writeString(replyPayload, Charset.UTF8)
        reply.resetForRead()
        stream.write(reply, 10.seconds)
        stream.close()
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

    /** How [readUpTo] stopped, when it stopped without throwing. */
    private enum class ReadEnding { AllArrived, End, Reset, TimedOut }

    /**
     * Accumulate stream reads until [total] characters have arrived, the stream ends, or a read runs
     * out of time — returning what did arrive, and telling [onEnded] which of those it was. A read
     * timeout is a *result* here, not a failure: the unfixed server simply never sends the rest, and
     * the assertion should be about the missing bytes rather than about a stack trace.
     */
    private suspend fun readUpTo(
        stream: QuicByteStream,
        total: Int,
        timeout: Duration,
        onEnded: (ReadEnding) -> Unit = {},
    ): String {
        val sb = StringBuilder(total)
        while (sb.length < total) {
            val r =
                try {
                    stream.read(timeout) { it.readString(it.remaining(), Charset.UTF8) }
                } catch (_: TimeoutCancellationException) {
                    onEnded(ReadEnding.TimedOut)
                    return sb.toString()
                }
            when (r) {
                is ScopedRead.Data -> sb.append(r.value)
                ScopedRead.End -> {
                    onEnded(ReadEnding.End)
                    return sb.toString()
                }
                ScopedRead.Reset -> {
                    onEnded(ReadEnding.Reset)
                    return sb.toString()
                }
            }
        }
        onEnded(ReadEnding.AllArrived)
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

        /**
         * How long the server must have sent nothing, after its handler returned, before
         * [ReplyBlackoutProxy] delivers its last datagram. The server closes within milliseconds of the
         * handler returning; this only has to outlast that, well inside the guard's 4 s read budget.
         */
        val SERVER_QUIET = 250.milliseconds

        /** ~8 KB ⇒ several datagrams, so a dropped prefix leaves a hole nothing else can fill. */
        const val REPLY_SIZE = 8 * 1024

        /** Deterministic single-byte-UTF8 payload, so a chunk boundary never splits a codepoint. */
        val replyPayload =
            buildString(REPLY_SIZE) {
                for (i in 0 until REPLY_SIZE) append('A' + (i % 26))
            }
    }
}
