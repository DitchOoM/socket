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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A client's socket that moves back to an address it left, as an OS may hand a new socket a port a closed one had,
 * is followed there like any other move: the client probes it afresh and migrates, and the server follows its peer
 * back to an address it saw before. On real core ends over virtual time.
 */
class CoreMoveBackVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun aSocketThatMovesBackToAPortItLeftMigratesThereAndCarriesStreams() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            launch { runCatching { while (true) echoOne(serverSide) } }
            var migrated = 0
            val counting = launch { while (true) migrated = migrations(pair) }
            val first = pair.clientPort()
            move(pair)
            pair.runUntil { migrated == 1 }
            pair.movePorts.addLast(first)
            val from = pair.now()
            move(pair)
            pair.runUntil(within = 5.seconds) { migrated == 2 }
            counting.cancel()
            assertEquals(first, pair.clientPort())
            assertTrue(pair.sentBy(End.Client, from).isNotEmpty(), "the client sent from the address it moved back to")
            var echoed = 0
            launch { echoed = echo(pair) }
            pair.runUntil(within = 5.seconds) { echoed > 0 }
            assertEquals(PAYLOAD, echoed, "the stream opened after the move back was echoed whole")
            var dumped = false
            var serverDrops = emptyList<TraceEvent.Drop>()
            launch {
                serverDrops = flight(serverSide).filterIsInstance<TraceEvent.Drop>()
                dumped = true
            }
            pair.runUntil { dumped }
            assertEquals(emptyList(), serverDrops, "the server had a socket for every datagram it wrote to its peer")
        }

    @Test
    fun whatQuicheWritesForThePathTheSocketLeftIsRecordedAsDroppedWithItsAddress() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            launch { runCatching { while (true) echoOne(serverSide) } }
            val left = pair.clientPort()
            move(pair)
            // Written while the new path is probed, for the path quiche is still active on, whose socket is gone.
            var echoed = 0
            launch { echoed = echo(pair) }
            pair.runUntil(within = 5.seconds) { echoed > 0 }
            assertEquals(PAYLOAD, echoed, "what was dropped was resent from the new path")
            var drops = emptyList<TraceEvent.Drop>()
            launch { drops = flight(pair).filterIsInstance<TraceEvent.Drop>() }
            pair.runUntil { drops.isNotEmpty() }
            assertTrue(drops.all { it.type == DropWhy.NoSocketForPath.type && it.message.contains(" address 4:$left:") }, "$drops")
        }

    private fun move(pair: VirtualPair) = pair.client.parts.migrate(ANY, RttPrior.CONFIGURED)

    private suspend fun flight(pair: VirtualPair): List<TraceEvent> = flight(pair.client.connection)

    private suspend fun flight(connection: CoreConnection): List<TraceEvent> {
        val recorded = ArrayList<TraceEvent>()
        connection.dumpFlight(TraceSink { recorded += it })
        return recorded
    }

    private suspend fun migrations(pair: VirtualPair): Int =
        flight(pair).filterIsInstance<TraceEvent.PathState>().count { it.phase == "Migrated" }

    /** Writes [PAYLOAD] bytes on a new stream and answers how many came back before the server's FIN. */
    private suspend fun echo(pair: VirtualPair): Int {
        val stream = pair.client.connection.openStream()
        stream.write(payload(), 1.seconds)
        (stream as HalfCloseable).shutdownSend()
        var got = 0
        while (true) {
            when (val read = stream.read(1.seconds)) {
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
                when (val read = stream.read(1.seconds)) {
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

        /** What the pair's socket is asked to bind to; it picks the port itself. */
        val ANY = PathKey(family = 4, port = 0, hi = 0L, lo = 0L)
    }
}
