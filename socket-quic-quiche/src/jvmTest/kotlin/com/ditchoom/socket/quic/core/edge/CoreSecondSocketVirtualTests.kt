package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.core.conn.DropWhy
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.read
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

    private suspend fun CoroutineScope.awaitMark(
        pair: VirtualPair,
        mark: String,
    ) {
        var seen = false
        val watching = launch { while (!seen) seen = mark in flight(pair).filterIsInstance<TraceEvent.PathState>().map { it.phase } }
        pair.runUntil(within = 5.seconds) { seen }
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

        /** Past three failed validations of the move and the waits between them. */
        val MOVE_GIVEN_UP = 20.seconds

        /** What the pair's socket is asked to bind to; it picks the port itself. */
        val ANY = PathKey(family = 4, port = 0, hi = 0L, lo = 0L)
    }
}
