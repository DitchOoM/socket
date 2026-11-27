package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.MigrateOutcome
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The connection carries on a new socket only once the loop promoted it. quiche migrates to the validated path in one
 * pass and the loop runs the promotion in the next; a second socket the loop loses in between was never promoted, so
 * the connection migrates back to the socket the loop still carries on, and every datagram it writes leaves by a
 * socket the loop holds. On real core ends over virtual time.
 */
class CorePromotionFactTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    /** The second socket's read fails as closed in the pass after quiche migrated to its path, before the promotion runs. */
    @Test
    fun aSecondSocketLostBeforeItsPromotionLeavesTheConnectionOnTheOldSocket() {
        val hook = OnMigration()
        VirtualPair.with(options, clientApi = { hook.over(it) }) { pair ->
            hook.then = { pair.secondReads = SecondReads.Dead }
            assertStaysOnTheOldSocket(pair, hook)
        }
    }

    /** A rehome whose second socket cannot be reopened reaches the loop ahead of the promotion quiche's migration asked for. */
    @Test
    fun aSecondSocketARehomeLosesBeforeItsPromotionLeavesTheConnectionOnTheOldSocket() {
        val hook = OnMigration()
        VirtualPair.with(options, clientApi = { hook.over(it) }) { pair ->
            hook.then = {
                pair.refuseSecondReopen = true
                pair.rehome()
            }
            assertStaysOnTheOldSocket(pair, hook)
        }
    }

    private suspend fun CoroutineScope.assertStaysOnTheOldSocket(
        pair: VirtualPair,
        hook: OnMigration,
    ) {
        val serverSide = pair.accept()
        launch { runCatching { while (true) echoOne(serverSide) } }
        val first = pair.clientPort()
        pair.client.parts.migrate(ANY, RttPrior.CONFIGURED, MOVE_NETWORK)
        pair.runUntil(within = 5.seconds) { hook.migrations > 0 }
        pair.runFor(ECHO_EVERY)
        assertEquals(1, pair.clientSockets, "the lost socket was closed")
        assertEquals(first, pair.clientPort(), "the loop carries on the socket it had")
        assertIs<EdgeEnd.Open>(pair.client.connection.end(), "a lost second socket does not end the connection")
        var echoed = 0
        launch { echoed = echo(pair) }
        pair.runFor(ECHO_WITHIN)
        assertEquals(0, pair.strayDatagrams, "the connection wrote for a socket the loop no longer holds")
        assertEquals(PAYLOAD, echoed, "the old socket carries on")
        val marks = marks(pair)
        assertTrue("Migrated" in marks && "SecondLost" in marks, "quiche migrated, and the move was lost: $marks")
        assertEquals(2, hook.migrations, "the connection migrated back to the old socket's path")
    }

    /** The client's quiche: once its first migration is taken, [then] runs, inside the connection's turn. */
    private class OnMigration {
        var then: () -> Unit = {}
        var migrations = 0
            private set

        fun over(inner: QuicheApi): QuicheApi =
            object : QuicheApi by inner {
                override fun connMigrate(
                    conn: QuicheConn,
                    localAddr: Long,
                    localLen: Int,
                    peerAddr: Long,
                    peerLen: Int,
                ): MigrateOutcome =
                    inner.connMigrate(conn, localAddr, localLen, peerAddr, peerLen).also {
                        if (it is MigrateOutcome.Migrated && migrations++ == 0) then()
                    }
            }
    }

    private suspend fun flight(pair: VirtualPair): List<TraceEvent> {
        val recorded = ArrayList<TraceEvent>()
        pair.client.connection.dumpFlight(TraceSink { recorded += it })
        return recorded
    }

    private suspend fun CoroutineScope.marks(pair: VirtualPair): List<String> {
        var events: List<TraceEvent>? = null
        launch { events = flight(pair) }
        pair.runUntil { events != null }
        return events.orEmpty().filterIsInstance<TraceEvent.PathState>().map { it.phase }
    }

    /** Writes [PAYLOAD] bytes on a new stream and answers how many came back before the server's FIN. */
    private suspend fun echo(pair: VirtualPair): Int {
        val stream = pair.client.connection.openStream()
        stream.write(payload(), 1.seconds)
        (stream as HalfCloseable).shutdownSend()
        var got = 0
        while (true) {
            when (val read = stream.read(2.seconds)) {
                is ReadResult.Data -> {
                    got += read.buffer.remaining()
                    read.buffer.freeIfNeeded()
                }
                else -> return got
            }
        }
    }

    private suspend fun CoroutineScope.echoOne(server: CoreConnection) {
        val stream = server.acceptStream()
        launch {
            while (true) {
                when (val read = stream.read(2.seconds)) {
                    is ReadResult.Data -> stream.write(read.buffer, 1.seconds)
                    else -> break
                }
            }
            (stream as HalfCloseable).shutdownSend()
        }
    }

    private fun payload(): PlatformBuffer =
        BufferFactory.Default.allocate(PAYLOAD).apply {
            repeat(PAYLOAD) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        const val PAYLOAD = 600
        val ECHO_EVERY = 500.milliseconds

        /** Many round trips of the pair's 60 ms: an echo on a socket that carries is back well within it. */
        val ECHO_WITHIN = 2.seconds

        /** What the pair's socket is asked to bind to; it picks the port itself. */
        val ANY = PathKey(family = 4, port = 0, hi = 0L, lo = 0L)
    }
}
