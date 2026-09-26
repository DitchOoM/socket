package com.ditchoom.socket.quic

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * [QuicScope]'s lifecycle contract, measured from inside the block on both ends of a real connection:
 * when the connection ends — the peer closes it, or it idles out — the block running on it is cancelled.
 *
 * Each block parks in `awaitCancellation()` and records the throwable that ends it, so a pass means the
 * block's own body observed its cancellation: nothing here can pass because a close was *called*, only
 * because the code running on the connection was stopped. Every failure names the connection state the
 * block was left running on, which separates "the close never arrived" from "it arrived and nothing
 * cancelled the block". A close the block made itself is the one end that does not cancel it, and
 * data the connection received and the block has not yet read keeps it running, for a bounded time.
 */
abstract class ConnectionEndCancelsScopeTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private val options = QuicOptions(alpnProtocols = listOf("scope-end-test"), verifyPeer = false)

    /** How long a peer's CONNECTION_CLOSE may take to end a block on loopback: far below any idle timeout. */
    private val closeBound: Duration = 5.seconds * testTimeScale()

    /** A block parked until something cancels it, recording what ended it and the state it ran on. */
    private class ParkedBlock {
        val entered = CompletableDeferred<StateFlow<QuicConnectionState>>()
        val ended = CompletableDeferred<Throwable>()

        suspend fun park(scope: QuicScope): Nothing {
            entered.complete((scope as QuicConnection).state)
            try {
                awaitCancellation()
            } catch (e: Throwable) {
                ended.complete(e)
                throw e
            }
        }

        suspend fun awaitEnd(
            within: Duration,
            what: String,
        ): Throwable {
            val started = TimeSource.Monotonic.markNow()
            return withTimeoutOrNull(within) { ended.await() }
                ?: fail(
                    "$what is still running ${started.elapsedNow()} after the connection ended; the connection " +
                        "it runs on is ${entered.getCompleted().value}",
                )
        }
    }

    private fun CoroutineScope.parkedServer(
        server: QuicServer,
        block: ParkedBlock,
    ) = launch { server.connections { block.park(this) } }

    @Test
    fun aServerHandlerIsCancelledWhenTheClientCloses() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                    val handler = ParkedBlock()
                    val serverJob = parkedServer(this, handler)
                    try {
                        withQuicConnection("127.0.0.1", port, options) {
                            withTimeout(closeBound) { handler.entered.await() }
                        }
                        // withQuicConnection's return has sent the client's NO_ERROR CONNECTION_CLOSE.
                        val ended = handler.awaitEnd(closeBound, "the server handler")
                        assertIs<CancellationException>(ended, "the server handler must end by being cancelled")
                    } finally {
                        serverJob.cancelAndJoin()
                    }
                }
            }
        }

    @Test
    fun aClientBlockIsCancelledWhenTheServerCloses() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                val serverOptions = options.copy(closeLinger = QuicCloseLinger.Immediate)
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = serverOptions) {
                    // The handler returns at once, so the server closes the connection with NO_ERROR.
                    val serverJob = launch { connections { } }
                    val block = ParkedBlock()
                    val clientOutcome = CompletableDeferred<Throwable?>()
                    val client =
                        launch {
                            val closed =
                                runCatching { withQuicConnection("127.0.0.1", port, options) { block.park(this) } }
                                    .exceptionOrNull()
                            // Recorded apart from the block's own end: this is what the caller of
                            // withQuicConnection is told, which must be the typed close, not a cancellation.
                            clientOutcome.complete(closed)
                        }
                    try {
                        withTimeout(closeBound) { block.entered.await() }
                        val ended = block.awaitEnd(closeBound, "the client block")
                        assertIs<CancellationException>(ended, "the client block must end by being cancelled")
                        val thrown = withTimeout(closeBound) { clientOutcome.await() }
                        val close = assertIs<QuicCloseException>(thrown, "withQuicConnection must report the peer's close, typed")
                        assertEquals(QuicCloseReason.Graceful, close.closeReason)
                    } finally {
                        client.cancelAndJoin()
                        serverJob.cancelAndJoin()
                    }
                }
            }
        }

    /**
     * The other side of the contract: a close the block asked for itself is not the connection dying, so
     * the block is left to unwind on its own and what it returns reaches the caller — a layered protocol
     * aborting with its own code reports its own typed error, not a cancellation.
     */
    @Test
    fun aBlockThatClosesTheConnectionItselfUnwindsOnItsOwn() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                    val serverJob = launch { connections { awaitCancellation() } }
                    try {
                        val result =
                            withQuicConnection("127.0.0.1", port, options) {
                                val state = (this as QuicConnection).state
                                closeWithError(APPLICATION_CLOSE_CODE)
                                val closed = withTimeout(closeBound) { state.first { it is QuicConnectionState.Closed } }
                                // Time for a watcher that saw the same Closed to cancel this block, were it to.
                                delay(WATCHER_GRACE)
                                closed
                            }
                        assertEquals(
                            QuicConnectionState.Closed(QuicCloseReason.ByLocal(QuicError.ApplicationError(APPLICATION_CLOSE_CODE))),
                            result,
                            "the block that closed its own connection must run to its own return",
                        )
                    } finally {
                        serverJob.cancelAndJoin()
                    }
                }
            }
        }

    @Test
    fun bothBlocksAreCancelledWhenTheConnectionIdlesOut() =
        runQuicTest(timeout = 40.seconds) {
            wrapTestBody {
                val idle = options.copy(idleTimeout = 1.seconds)
                // The idle timer fires at the idle timeout, give or take a PTO; a block still running well
                // past it was never told.
                val idleBound = 10.seconds * testTimeScale()
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = idle) {
                    val handler = ParkedBlock()
                    val serverJob = parkedServer(this, handler)
                    val block = ParkedBlock()
                    val clientOutcome = CompletableDeferred<Throwable?>()
                    val client =
                        launch {
                            clientOutcome.complete(
                                runCatching { withQuicConnection("127.0.0.1", port, idle) { block.park(this) } }
                                    .exceptionOrNull(),
                            )
                        }
                    try {
                        withTimeout(closeBound) { block.entered.await() }
                        withTimeout(closeBound) { handler.entered.await() }
                        assertIs<CancellationException>(block.awaitEnd(idleBound, "the client block"))
                        assertIs<CancellationException>(handler.awaitEnd(idleBound, "the server handler"))
                        val thrown = withTimeout(closeBound) { clientOutcome.await() }
                        val close = assertIs<QuicCloseException>(thrown, "withQuicConnection must report the idle close, typed")
                        assertEquals(QuicCloseReason.ByLocal(QuicError.IdleTimeout), close.closeReason)
                    } finally {
                        client.cancelAndJoin()
                        serverJob.cancelAndJoin()
                    }
                }
            }
        }

    /**
     * The reply-then-close pattern with a slow client: the server writes its reply, FINs and returns, and
     * its CONNECTION_CLOSE follows. The client does not read until well past its draining period (3 × PTO),
     * so the connection has died with the reply unread — and the block, still running, reads it in full
     * and returns it. A block cancelled the moment the connection read Closed would lose the reply.
     */
    @Test
    fun aClientThatReadsAfterTheServerClosedStillGetsTheWholeReply() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                // Short enough that the server closes long before the client reads; long enough for the
                // whole reply to cross loopback first.
                val serverOptions = options.copy(closeLinger = QuicCloseLinger.UntilPeerDone(SERVER_LINGER))
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = serverOptions) {
                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                val request = stream.readToEnd()
                                writeText(stream, request.repeat(REPLY_REPEAT))
                                stream.close()
                            }
                        }
                    // Where the client block was, and what it had, if it ended without the reply.
                    var step = "connecting"
                    val got = StringBuilder()
                    var unread: () -> QuicUnreadAtClose = { QuicUnreadAtClose.ConnectionOpen }
                    try {
                        val reply =
                            try {
                                withQuicConnection("127.0.0.1", port, options) {
                                    val connection = this as QuicConnection
                                    unread = { connection.unreadAtClose.value }
                                    val stream = openStream()
                                    step = "writing the request"
                                    writeText(stream, REQUEST)
                                    stream.shutdownSend()
                                    step = "waiting for the server's close"
                                    withTimeout(closeBound) { connection.state.first { it is QuicConnectionState.Closed } }
                                    step = "waiting before reading"
                                    delay(SLOW_READER)
                                    step = "reading"
                                    stream.readToEnd(got)
                                }
                            } catch (e: QuicCloseException) {
                                fail("the client block ended $step with ${got.length} chars read, unread-at-close ${unread()}: $e")
                            }
                        assertEquals(REQUEST.repeat(REPLY_REPEAT).length, reply.length, "the reply must arrive in full")
                        assertEquals(REQUEST.repeat(REPLY_REPEAT), reply)
                    } finally {
                        serverJob.cancelAndJoin()
                    }
                }
            }
        }

    /**
     * The same pattern the other way round: the client sends its request, FINs and closes, and the server's
     * handler does not read until well past its draining period. The request is still delivered in full.
     */
    @Test
    fun aServerHandlerThatReadsAfterTheClientClosedStillGetsTheWholeRequest() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                    // The request fits one packet: the stream reaching the handler means all of it arrived.
                    val accepted = CompletableDeferred<Unit>()
                    val received = CompletableDeferred<String>()
                    // How the handler ended when it did not read the request: the step it was on, and what
                    // the connection said then — the difference between a close that never arrived and one
                    // that cancelled a handler with the request still unread.
                    val handlerEnded = CompletableDeferred<String>()
                    val serverJob =
                        launch {
                            connections {
                                val connection = this as QuicConnection
                                var step = "accepting the stream"
                                try {
                                    val stream = acceptStream()
                                    accepted.complete(Unit)
                                    step = "waiting for the client's close"
                                    withTimeout(closeBound) { connection.state.first { it is QuicConnectionState.Closed } }
                                    step = "waiting before reading"
                                    delay(SLOW_READER)
                                    step = "reading"
                                    received.complete(stream.readToEnd())
                                } catch (e: Throwable) {
                                    // What quiche itself holds, read on the loop: whether the client's
                                    // CONNECTION_CLOSE was received, and which timer the connection waits on.
                                    val quiche =
                                        withContext(NonCancellable) {
                                            (connection as QuicheBackedConnection).quicheDriver.inspect { api, conn ->
                                                "peerError=${api.connPeerError(conn)} closed=${api.connIsClosed(conn)} " +
                                                    "timeout=${api.connTimeout(conn)} stats=${api.connStats(conn)}"
                                            }
                                        }
                                    handlerEnded.complete(
                                        "$step: $e (state=${connection.state.value}, unread=${connection.unreadAtClose.value}, " +
                                            "quiche: $quiche)",
                                    )
                                    throw e
                                }
                            }
                        }
                    try {
                        withQuicConnection("127.0.0.1", port, options) {
                            val stream = openStream()
                            writeText(stream, REQUEST)
                            stream.shutdownSend()
                            withTimeout(closeBound) { accepted.await() }
                        }
                        // withQuicConnection's return has closed the connection under the parked handler.
                        val request =
                            withTimeoutOrNull(closeBound + SLOW_READER) { received.await() }
                                ?: fail(
                                    "the server handler did not read the request the client sent; it ended " +
                                        (if (handlerEnded.isCompleted) handlerEnded.getCompleted() else "never (still running)"),
                                )
                        assertEquals(REQUEST, request)
                    } finally {
                        serverJob.cancelAndJoin()
                    }
                }
            }
        }

    /**
     * Unread data keeps a block on a dead connection running only for a bounded time: a block that never
     * reads the reply is still cancelled, within the idle timeout, with the typed close.
     */
    @Test
    fun aBlockThatNeverReadsIsCancelledWithinTheIdleTimeout() =
        runQuicTest(timeout = 40.seconds) {
            wrapTestBody {
                val clientOptions = options.copy(idleTimeout = LINGER_IDLE_TIMEOUT)
                val serverOptions = options.copy(closeLinger = QuicCloseLinger.Immediate)
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = serverOptions) {
                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                stream.readToEnd()
                                writeText(stream, REQUEST)
                                stream.close()
                            }
                        }
                    val block = ParkedBlock()
                    val unreadWhenClosed = CompletableDeferred<QuicUnreadAtClose>()
                    val outcome = CompletableDeferred<Throwable?>()
                    val client =
                        launch {
                            outcome.complete(
                                runCatching {
                                    withQuicConnection("127.0.0.1", port, clientOptions) {
                                        val connection = this as QuicConnection
                                        launch {
                                            connection.state.first { it is QuicConnectionState.Closed }
                                            unreadWhenClosed.complete(connection.unreadAtClose.value)
                                        }
                                        val stream = openStream()
                                        writeText(stream, REQUEST)
                                        stream.shutdownSend()
                                        block.park(this)
                                    }
                                }.exceptionOrNull(),
                            )
                        }
                    try {
                        withTimeout(closeBound) { block.entered.await() }
                        val unread = withTimeout(closeBound) { unreadWhenClosed.await() }
                        assertIs<QuicUnreadAtClose.Unread>(
                            unread,
                            "the reply must be unread when the connection closes, or this proves nothing",
                        )
                        val ended = block.awaitEnd(LINGER_IDLE_TIMEOUT + closeBound, "a block that never reads its reply")
                        assertIs<CancellationException>(ended)
                        val close = assertIs<QuicCloseException>(withTimeout(closeBound) { outcome.await() })
                        assertEquals(QuicCloseReason.Graceful, close.closeReason)
                    } finally {
                        client.cancelAndJoin()
                        serverJob.cancelAndJoin()
                    }
                }
            }
        }

    private suspend fun QuicByteStream.readToEnd(text: StringBuilder = StringBuilder()): String {
        while (true) {
            when (val r = read(closeBound) { it.readString(it.remaining(), Charset.UTF8) }) {
                is ScopedRead.Data -> text.append(r.value)
                ScopedRead.End -> return text.toString()
                ScopedRead.Reset -> fail("the peer reset a stream it was answering on, after ${text.length} chars")
            }
        }
    }

    /** The whole of [text], across as many partial writes as flow control and congestion make it take. */
    private suspend fun QuicScope.writeText(
        stream: QuicByteStream,
        text: String,
    ) {
        val out = bufferFactory.allocate(text.length)
        try {
            out.writeString(text, Charset.UTF8)
            out.resetForRead()
            stream.writeFully(out, closeBound)
        } finally {
            out.freeIfNeeded()
        }
    }

    private companion object {
        const val REQUEST = "reply-then-close;"
        const val REPLY_REPEAT = 2048
        val SERVER_LINGER = 300.milliseconds

        /** Well past a loopback connection's draining period (3 × PTO, tens of milliseconds). */
        val SLOW_READER = 2.seconds
        val LINGER_IDLE_TIMEOUT = 3.seconds
        const val APPLICATION_CLOSE_CODE = 0x7L
        val WATCHER_GRACE = 250.milliseconds
    }
}
