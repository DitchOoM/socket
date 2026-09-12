package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.socket.ConnectionState
import com.ditchoom.socket.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What happens after the inner stream ends cleanly — the one path that publishes
 * [ConnectionState.Disconnected] without releasing the connection it holds.
 *
 * Every other exit releases it. This one looks like an omission and is not, and these tests are what
 * says which: the retained connection is the only reference left, so releasing it here would leak the
 * connection instead of closing it.
 */
class ReconnectingConnectionCleanEndTests {
    /** Its `receive()` completes immediately and normally — a clean stream end, no error. */
    private class CleanEndConnection : Connection<String> {
        override val id: Long = 0L
        var closeCount = 0
            private set

        override suspend fun send(message: String) = Unit

        override suspend fun close() {
            closeCount++
        }

        override fun receive(): Flow<String> = emptyFlow()
    }

    /**
     * The reason the clean-end path keeps its connection: nothing else is holding one.
     *
     * `conn.receive()` completing does not close `conn` — a finished Flow is not a closed transport.
     * If this path released the slot, the last reference would go with it and the connection would be
     * left open with nobody able to close it. Keeping it is what lets [ReconnectingConnection.close]
     * still do its job afterwards.
     */
    @Test
    fun closeAfterACleanStreamEndStillClosesTheConnection() =
        runTest {
            val connections = mutableListOf<CleanEndConnection>()
            val connection =
                ReconnectingConnection(
                    connect = { CleanEndConnection().also { connections += it } },
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                )

            connection.receive().collect { }
            assertEquals(1, connections.size, "expected exactly one connection to be minted")
            assertEquals(
                0,
                connections.single().closeCount,
                "the clean-end path should not close the connection itself",
            )

            connection.close()

            assertEquals(
                1,
                connections.single().closeCount,
                "close() after a clean stream end did not close the inner connection — the " +
                    "clean-end path is the only thing still holding a reference to it, so if it " +
                    "released the slot the connection would be leaked open",
            )
        }

    /**
     * And the reason it is NOT kept for [ReconnectingConnection.send]'s benefit.
     *
     * `send()` requires `collecting.isLocked` — a live collector drives reconnection, so sending
     * without one has nothing to reconnect it. A clean end returns from the flow, whose `finally`
     * unlocks `collecting`, so `send()` is unusable from that moment regardless of what the slot
     * holds. Recorded because "the write side may still be usable" is the plausible-sounding reason
     * to keep the connection, and it is wrong.
     */
    @Test
    fun sendAfterACleanStreamEndIsRefused() =
        runTest {
            val connection =
                ReconnectingConnection(
                    connect = { CleanEndConnection() },
                    monitorFactory = { NetworkMonitor.AlwaysAvailable },
                )

            connection.receive().collect { }

            val refusal = assertFailsWith<IllegalStateException> { connection.send("after clean end") }
            assertTrue(
                refusal.message?.contains("receive()") == true,
                "expected the no-collector refusal, got: ${refusal.message}",
            )
        }
}
