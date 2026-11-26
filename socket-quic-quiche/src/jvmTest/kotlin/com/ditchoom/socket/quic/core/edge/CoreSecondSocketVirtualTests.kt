package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.core.conn.DropWhy
import com.ditchoom.socket.quic.core.loop.closeSecond
import com.ditchoom.socket.quic.core.loop.move
import com.ditchoom.socket.quic.core.loop.promote
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A client's move is made before it breaks: the socket on the new address opens beside the one carrying the
 * connection, which keeps carrying until the new path validates. On real core ends over virtual time.
 */
class CoreSecondSocketVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    /**
     * The new address routes nothing: its path never validates, the connection keeps echoing on the old socket through
     * the probes, the move is given up, and the connection stays on the address it had, open.
     */
    @Test
    fun aMoveWhosePathNeverValidatesLeavesTheConnectionEchoingOnTheOldSocket() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            launch { runCatching { while (true) echoOne(serverSide) } }
            val first = pair.clientPort()
            pair.deadClientPorts += first + 1
            move(pair)
            var echoes = 0
            val from = pair.now()
            while (pair.now() - from < MOVE_GIVEN_UP.inWholeNanoseconds) {
                var echoed = 0
                launch { echoed = echo(pair) }
                pair.runUntil(within = 2.seconds) { echoed > 0 }
                assertEquals(PAYLOAD, echoed, "echo ${echoes + 1} came back whole on the old socket")
                echoes++
                pair.runFor(ECHO_EVERY)
            }
            assertEquals(first, pair.clientPort(), "the connection stayed on the socket it had")
            assertIs<EdgeEnd.Open>(pair.client.connection.end())
            val marks = marks(pair)
            assertTrue("Abandoned" in marks && "Migrated" !in marks, "the move was given up: $marks")
        }

    /** While the new path is probed, what quiche writes for the old one leaves by the old socket: nothing is dropped. */
    @Test
    fun aMoveDropsNothingTheOldPathCarriesWhileTheNewOneIsProbed() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            launch { runCatching { while (true) echoOne(serverSide) } }
            move(pair)
            // The echo is written once the new socket is open and its path probed, before the path validates.
            awaitMark(pair, "Moved")
            var echoed = 0
            launch { echoed = echo(pair) }
            pair.runUntil(within = 5.seconds) { echoed > 0 }
            assertEquals(PAYLOAD, echoed)
            awaitMark(pair, "Migrated")
            val drops = recorded(pair).filterIsInstance<TraceEvent.Drop>().filter { it.type == DropWhy.NoSocketForPath.type }
            assertEquals(emptyList(), drops, "every datagram quiche wrote had a socket")
        }

    /**
     * The second socket cannot be opened, as at an address the host does not hold: the move is refused and recorded, and
     * the connection stays on the socket it had, open and echoing.
     */
    @Test
    fun aMoveWhoseSocketCannotBeOpenedStaysOnTheOldSocket() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            launch { runCatching { while (true) echoOne(serverSide) } }
            val first = pair.clientPort()
            pair.refuseOpens = true
            move(pair)
            awaitMark(pair, "OpenRefused")
            pair.runFor(ECHO_EVERY)
            assertIs<EdgeEnd.Open>(pair.client.connection.end(), "a refused move does not end the connection")
            assertEquals(first, pair.clientPort())
            assertEquals(1, pair.clientSockets)
            var echoed = 0
            launch { echoed = echo(pair) }
            pair.runUntil(within = 2.seconds) { echoed > 0 }
            assertEquals(PAYLOAD, echoed, "the old socket carries on")
        }

    /**
     * The peer forbade migration (`disable_active_migration`): the connection cannot follow the second socket, which is
     * closed; the move is recorded as refused and the connection stays on the socket it had, open and echoing.
     */
    @Test
    fun aMoveThePeerForbidsStaysOnTheOldSocket() =
        VirtualPair.with(options.copy(migration = MigrationPolicy.Forbidden)) { pair ->
            val serverSide = pair.accept()
            launch { runCatching { while (true) echoOne(serverSide) } }
            val first = pair.clientPort()
            move(pair)
            awaitMark(pair, "Refused")
            pair.runFor(ECHO_EVERY)
            assertIs<EdgeEnd.Open>(pair.client.connection.end(), "a move the peer forbids does not end the connection")
            assertEquals(first, pair.clientPort())
            assertEquals(1, pair.clientSockets, "the second socket was closed")
            var echoed = 0
            launch { echoed = echo(pair) }
            pair.runUntil(within = 2.seconds) { echoed > 0 }
            assertEquals(PAYLOAD, echoed, "the old socket carries on")
        }

    /** The abandoned move's socket is closed: the client holds one socket again, and never held more than two. */
    @Test
    fun anAbandonedMoveClosesOnlyItsSecondSocket() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            launch { runCatching { while (true) echoOne(serverSide) } }
            val first = pair.clientPort()
            pair.deadClientPorts += first + 1
            move(pair)
            pair.runUntil(within = 2.seconds) { pair.clientSockets == 2 }
            awaitMark(pair, "Abandoned", within = MOVE_GIVEN_UP)
            pair.runFor(ECHO_EVERY)
            assertEquals(1, pair.clientSockets, "the probe's socket was closed")
            assertEquals(1, pair.secondsClosed)
            assertEquals(2, pair.mostClientSockets)
            assertEquals(first, pair.clientPort(), "the carrying socket was left open")
            var echoed = 0
            launch { echoed = echo(pair) }
            pair.runUntil(within = 2.seconds) { echoed > 0 }
            assertEquals(PAYLOAD, echoed)
        }

    /**
     * Moves that follow each other before any validates never hold a third socket: each opens its socket only once the
     * loop closed the one before.
     */
    @Test
    fun movesThatFollowEachOtherNeverHoldAThirdSocket() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            launch { runCatching { while (true) echoOne(serverSide) } }
            val first = pair.clientPort()
            for (port in first + 1..first + MOVES) pair.deadClientPorts += port
            repeat(MOVES) {
                move(pair)
                pair.runFor(PROBE_FOR)
            }
            assertEquals(2, pair.mostClientSockets, "a move opened its socket only once the one before was closed")
            assertEquals(MOVES - 1, pair.secondsClosed)
            pair.deadClientPorts.clear()
            pair.movePorts.addLast(first + MOVES + 1)
            move(pair)
            awaitMark(pair, "Migrated")
            assertEquals(first + MOVES + 1, pair.clientPort())
            assertEquals(1, pair.clientSockets)
            var echoed = 0
            launch { echoed = echo(pair) }
            pair.runUntil(within = 2.seconds) { echoed > 0 }
            assertEquals(PAYLOAD, echoed)
        }

    /** A promotion or a close naming a ticket the loop no longer holds open changes nothing. */
    @Test
    fun aStaleTicketIsIgnored() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            launch { runCatching { while (true) echoOne(serverSide) } }
            val first = pair.clientPort()
            move(pair)
            awaitMark(pair, "Migrated")
            val moved = pair.clientPort()
            pair.deadClientPorts += moved + 1
            move(pair)
            pair.runUntil(within = 2.seconds) { pair.clientSockets == 2 }
            val loop = pair.client.loop
            val endpoint = pair.client.parts.loopEndpoint
            // The first socket's ticket, and the first move's, which carries now: neither names the open second socket.
            loop.promote(endpoint, SocketTicket.FIRST)
            loop.closeSecond(endpoint, SocketTicket.FIRST)
            loop.promote(endpoint, SocketTicket.FIRST.next())
            loop.closeSecond(endpoint, SocketTicket.FIRST.next())
            pair.runFor(ECHO_EVERY)
            assertEquals(2, pair.clientSockets, "the second socket stayed open")
            assertEquals(moved, pair.clientPort(), "the carrying socket stayed the carrying one")
            assertTrue(moved != first)
            var echoed = 0
            launch { echoed = echo(pair) }
            pair.runUntil(within = 2.seconds) { echoed > 0 }
            assertEquals(PAYLOAD, echoed)
        }

    /** A connection released while a move is open closes both of its sockets. */
    @Test
    fun releaseClosesBothSockets() =
        VirtualPair.with(options) { pair ->
            pair.deadClientPorts += pair.clientPort() + 1
            move(pair)
            pair.runUntil(within = 2.seconds) { pair.clientSockets == 2 }
            pair.client.connection.closeNow(com.ditchoom.socket.quic.QuicError.NoError)
            pair.runUntil { pair.client.connection.end() is EdgeEnd.Ended && pair.clientSockets == 0 }
        }

    private suspend fun CoroutineScope.awaitMark(
        pair: VirtualPair,
        mark: String,
        within: Duration = 5.seconds,
    ) {
        var seen = false
        val watching =
            launch {
                while (!seen) {
                    seen = mark in flight(pair).filterIsInstance<TraceEvent.PathState>().map { it.phase }
                    // A dump of an ended connection answers at once: the pair's passes must still run between reads.
                    yield()
                }
            }
        pair.runUntil(within = within) { seen }
        watching.cancel()
    }

    private fun move(pair: VirtualPair) = pair.client.parts.migrate(ANY, RttPrior.CONFIGURED)

    private suspend fun flight(pair: VirtualPair): List<TraceEvent> {
        val recorded = ArrayList<TraceEvent>()
        pair.client.connection.dumpFlight(TraceSink { recorded += it })
        return recorded
    }

    /** The client's flight recording, dumped while the pair runs. */
    private suspend fun CoroutineScope.recorded(pair: VirtualPair): List<TraceEvent> {
        var events: List<TraceEvent>? = null
        launch { events = flight(pair) }
        pair.runUntil { events != null }
        return events.orEmpty()
    }

    private suspend fun CoroutineScope.marks(pair: VirtualPair): List<String> =
        recorded(pair).filterIsInstance<TraceEvent.PathState>().map { it.phase }

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
        const val MOVES = 4

        /** Long enough for a move's socket to open and its path to be probed, short of its validation failing. */
        val PROBE_FOR = 300.milliseconds

        /** Past three failed validations of the move and the waits between them. */
        val MOVE_GIVEN_UP = 20.seconds

        /** What the pair's socket is asked to bind to; it picks the port itself. */
        val ANY = PathKey(family = 4, port = 0, hi = 0L, lo = 0L)
    }
}
