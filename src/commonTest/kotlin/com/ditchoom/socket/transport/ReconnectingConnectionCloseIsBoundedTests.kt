package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * `close()` returns even when the connection it is closing never does.
 *
 * #485 made this teardown [kotlinx.coroutines.NonCancellable], which is correct — the canonical call
 * site is `finally { close() }`, and without it a cancelled caller skipped teardown and leaked the
 * connection. But NonCancellable and *unbounded* together are the one combination the sibling classes
 * explicitly forbid: `CodecConnection.runTeardown` and `CodecSender.runTeardown` both wrap every
 * blocking call in `withTimeoutOrNull(config.io.outboundDrainOnClose)`, commented "an unbounded close
 * here would be UNKILLABLE".
 *
 * This class closes whatever the caller's `connect` lambda returned. That is arbitrary user code, so
 * the bound matters *more* here than in the siblings, not less — and #485 shipped without it. A
 * connection whose `close()` never returns would hang `close()` forever with nothing able to
 * interrupt it.
 */
class ReconnectingConnectionCloseIsBoundedTests {
    /** Its `close()` never returns — the shape the bound exists for. */
    private class NeverClosingConnection : Connection<String> {
        override val id: Long = 0L
        private val never = CompletableDeferred<Unit>()
        val closeAttempted = CompletableDeferred<Unit>()

        override suspend fun send(message: String) = Unit

        override suspend fun close() {
            closeAttempted.complete(Unit)
            never.await()
        }

        override fun receive(): Flow<String> = flow { never.await() }
    }

    @Test
    fun closeReturnsEvenWhenTheConnectionsCloseNeverDoes() =
        runTest {
            val connected = CompletableDeferred<Unit>()
            val hung = NeverClosingConnection()
            val connection =
                ReconnectingConnection(
                    connect = { hung.also { connected.complete(Unit) } },
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                    // A short budget so the test states the property rather than waiting out the
                    // 2s default; runTest's virtual clock skips the wait either way.
                    config = TransportConfig(io = TransportConfig().io.copy(outboundDrainOnClose = 50.milliseconds)),
                )

            val collector = launch { runCatching { connection.receive().collect { } } }
            connected.await()

            // The assertion is simply that this returns. Before the bound it never would, and
            // NonCancellable means no withTimeout around it could rescue the caller either.
            connection.close()

            assertTrue(
                hung.closeAttempted.isCompleted,
                "close() returned without ever attempting to close the connection it held",
            )
            collector.cancel()
        }
}
