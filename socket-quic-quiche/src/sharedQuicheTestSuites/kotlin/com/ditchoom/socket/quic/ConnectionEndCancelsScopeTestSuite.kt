package com.ditchoom.socket.quic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * cancelled the block". A close the block made itself is the one end that does not cancel it.
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

    private companion object {
        const val APPLICATION_CLOSE_CODE = 0x7L
        val WATCHER_GRACE = 250.milliseconds
    }
}
