package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A client that moves and has no spare destination id never reuses an old one on the new path (RFC 9000 §9.5): it waits
 * while its peer can still hand it one, and ends as NoUsablePath the moment none can come.
 */
class CoreStrandedVirtualTests {
    private val limitTwo =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            enablePmtuDiscovery = false,
            activeConnectionIdLimit = 2,
        )

    @Test
    fun aMoveWithNoSpareIdOnADeadLinkEndsAtOnceAsNoUsablePathWithoutSendingOnTheNewPath() =
        VirtualPair.with(limitTwo) { pair ->
            pair.runFor(1.seconds)
            assertEquals(1L, pair.clientSpareIds(), "one spare at the limit of two")
            pair.lose(End.Client, 100_000)
            pair.lose(End.Server, 100_000)
            move(pair)
            pair.runFor(2.seconds)
            assertIs<EdgeEnd.Open>(pair.client.connection.end(), "a probe in flight is no reason to end")
            val before = pair.sentBy(End.Client).size
            move(pair)
            pair.runUntil(within = 1.milliseconds) { pair.client.connection.end() is EdgeEnd.Ended }
            assertEquals(ConnEnd.NoUsablePath, assertIs<EdgeEnd.Ended>(pair.client.connection.end()).end)
            assertEquals(before, pair.sentBy(End.Client).size, "nothing left the client on the new path")
        }

    @Test
    fun aMoveWithNoSpareIdOnALiveLinkWaitsForTheIdAndMigrates() =
        VirtualPair.with(limitTwo, oneWay = 100.milliseconds) { pair ->
            val serverSide = pair.accept()
            var migrated = 0
            val counting =
                launch {
                    while (true) {
                        migrated = marks(pair).count { it == "Migrated" }
                        yield()
                    }
                }
            try {
                move(pair)
                pair.runUntil { migrated == 1 }
                // The id the first move retired is not replaced yet: its RETIRE_CONNECTION_ID has only just reached the peer.
                pair.runFor(110.milliseconds)
                assertEquals(0L, pair.clientSpareIds())
                move(pair)
                val ended = pair.client.connection.end()
                assertIs<EdgeEnd.Open>(ended, "no id yet, but the peer was heard a moment ago")
                pair.runFor(2.seconds)
                val after = pair.client.connection.end()
                assertIs<EdgeEnd.Open>(after, "ended: ${(after as? EdgeEnd.Ended)?.end} spare=${pair.clientSpareIds()} migrated=$migrated")
                pair.runUntil(within = 5.seconds) { migrated == 2 }
                assertIs<EdgeEnd.Open>(pair.client.connection.end())
                assertIs<EdgeEnd.Open>(serverSide.end())
            } finally {
                counting.cancel()
            }
            var seen = emptyList<String>()
            launch { seen = marks(pair) }
            pair.runUntil { seen.isNotEmpty() }
            assertTrue("Reprobed" in seen, "the move waited for an id: $seen")
        }

    @Test
    fun theServerIssuesSparesUpToThePeersLimit() =
        VirtualPair.with(limitTwo.copy(activeConnectionIdLimit = 8)) { pair ->
            pair.runFor(1.seconds)
            assertEquals(7L, pair.clientSpareIds())
        }

    private fun move(pair: VirtualPair) = pair.client.parts.migrate(ANY, RttPrior.CONFIGURED)

    private suspend fun marks(pair: VirtualPair): List<String> {
        val recorded = ArrayList<TraceEvent>()
        pair.client.connection.dumpFlight(TraceSink { recorded += it })
        return recorded.filterIsInstance<TraceEvent.PathState>().map { it.phase }
    }

    private companion object {
        val ANY = PathKey(family = 4, port = 0, hi = 0L, lo = 0L)
    }
}
