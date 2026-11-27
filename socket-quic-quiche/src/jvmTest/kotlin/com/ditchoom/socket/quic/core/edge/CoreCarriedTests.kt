package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.replay.conditions.LOCALHOST
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Where the client's socket is, as the edge reads it from the loop: the network feed on a scripted monitor and a real
 * core pair over virtual time. A refused move leaves the socket where it was, so the route still leaving from another
 * address is asked again and a new network is never a reopen of the carrying socket; a move the feed did not ask for
 * is read once it landed; and a rehome named for a socket promoted away reopens nothing.
 */
class CoreCarriedTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun aRefusedMoveIsAskedAgainWhileTheRouteStillLeavesFromTheOtherAddress() =
        VirtualPair.with(options) { pair ->
            val feed = Feed(pair)
            feed.start(this)
            pair.refuseOpens = true
            feed.route = FROM_OTHER
            feed.monitor.state.value = NetworkState.Routable(CELLULAR, InternetAccess.Observed.Pending)
            pair.runUntil(within = 2.seconds) { pair.opensAsked == 1 }
            pair.runFor(SETTLED)
            assertTrue("OpenRefused" in marks(pair), "precondition: the move was refused: ${marks(pair)}")
            feed.monitor.state.value = NetworkState.Routable(CELLULAR, InternetAccess.Observed.Confirmed)
            pair.runFor(SETTLED)
            assertEquals(2, pair.opensAsked, "the route still leaves from the other address, and the move was not asked again")
            assertIs<EdgeEnd.Open>(pair.client.connection.end())
            feed.stop()
        }

    @Test
    fun aNewNetworkIdAfterARefusedMoveDoesNotReopenTheCarryingSocket() =
        VirtualPair.with(options) { pair ->
            val feed = Feed(pair)
            feed.start(this)
            pair.refuseOpens = true
            feed.route = FROM_OTHER
            feed.monitor.state.value = NetworkState.Routable(CELLULAR, InternetAccess.Observed.Pending)
            pair.runUntil(within = 2.seconds) { pair.opensAsked == 1 }
            pair.runFor(SETTLED)
            feed.monitor.state.value = NetworkState.Routable(CELLULAR_AGAIN, InternetAccess.Observed.Pending)
            pair.runFor(SETTLED)
            assertEquals(0, pair.carryingReopens, "the carrying socket was reopened on a network whose route leaves from elsewhere")
            assertEquals(2, pair.opensAsked, "the move toward the other address was not asked on the new network")
            assertIs<EdgeEnd.Open>(pair.client.connection.end())
            feed.stop()
        }

    @Test
    fun theFeedLearnsAPromotionItDidNotAskFor() =
        VirtualPair.with(options) { pair ->
            val feed = Feed(pair)
            feed.start(this)
            pair.moveHosts.addLast(OTHER_KEY)
            val first = pair.clientPort()
            pair.client.parts.migrate(OTHER_KEY, RttPrior.CONFIGURED, MOVE_NETWORK)
            pair.runUntil(within = 5.seconds) { pair.clientPort() != first && pair.clientSockets == 1 }
            pair.runFor(SETTLED)
            feed.monitor.state.value = NetworkState.Offline
            pair.runFor(SETTLED)
            feed.route = FROM_OTHER
            feed.monitor.state.value = NetworkState.Routable(WIFI, InternetAccess.Observed.Confirmed)
            pair.runFor(SETTLED)
            assertEquals(1, pair.opensAsked, "the reading on the address the socket landed on asked for a move")
            assertEquals(0, pair.carryingReopens, "the socket that landed was reopened")
            val marks = marks(pair)
            assertEquals("Usable", marks.last { it == "Usable" || it == "Gone" }, "the connection was not told it can send: $marks")
            assertIs<EdgeEnd.Open>(pair.client.connection.end())
            feed.stop()
        }

    /**
     * A rehome read from where the socket was before a promotion reaches the loop after it: the socket it names no
     * longer carries, so nothing is reopened. Reopening the just-promoted socket instead, refused here, would end the
     * connection.
     */
    @Test
    fun aRehomeNamingASocketPromotedAwayReopensNothing() =
        VirtualPair.with(options) { pair ->
            val first = pair.clientPort()
            val earlier =
                pair.client.parts.carried
                    .read()
            pair.client.parts.migrate(ANY, RttPrior.CONFIGURED, MOVE_NETWORK)
            pair.runUntil(within = 5.seconds) { pair.clientPort() != first && pair.clientSockets == 1 }
            assertTrue(
                pair.client.parts.carried
                    .read()
                    .ticket != earlier.ticket,
                "precondition: the loop published the promotion",
            )
            pair.refuseCarryingReopen = true
            pair.client.parts.rehome(earlier.ticket, REHOME_NETWORK)
            pair.runFor(SETTLED)
            assertEquals(0, pair.carryingReopens, "the promoted socket was reopened for a rehome that named the old one")
            assertIs<EdgeEnd.Open>(pair.client.connection.end(), "a stale rehome ended the connection")
        }

    /**
     * The path goes silent and the rebind toward cellular's address cannot open its socket: the socket stays on Wi-Fi,
     * as the loop publishes it, so the monitor then naming cellular with the route from there is a move, asked again,
     * never a reopen of the Wi-Fi socket on cellular.
     */
    @Test
    fun aRebindThatDidNotLandLeavesTheNextReadingAMoveNeverAReopen() =
        VirtualPair.with(options) { pair ->
            pair.accept()
            val feed = Feed(pair)
            feed.start(this)
            pair.refuseOpens = true
            feed.route = FROM_OTHER
            pair.deadClientPorts += pair.clientPort()
            launch {
                runCatching {
                    while (true) {
                        pair.client.connection
                            .openStream()
                            .write(payload(), 1.seconds)
                    }
                }
            }
            pair.runUntil(within = 20.seconds) { pair.opensAsked >= 1 }
            pair.runFor(SETTLED)
            assertTrue("RebindForbidden" !in marks(pair) && "OpenRefused" in marks(pair), "precondition: ${marks(pair)}")
            val asked = pair.opensAsked
            feed.monitor.state.value = NetworkState.Routable(CELLULAR, InternetAccess.Observed.Pending)
            pair.runFor(SETTLED)
            assertEquals(0, pair.carryingReopens, "the Wi-Fi socket was reopened on cellular")
            assertTrue(pair.opensAsked > asked, "the handoff onto cellular was not asked as a move")
            feed.stop()
        }

    /**
     * The network under the carrying socket's address changes while a move toward another one is open: the rehome names
     * the carrying socket, which is leaving, so neither socket is reopened; the second keeps the network it was asked on.
     */
    @Test
    fun aRehomeNamingTheCarryingSocketDuringAMoveReopensNeither() =
        VirtualPair.with(options) { pair ->
            val first = pair.clientPort()
            pair.deadClientPorts += first + 1
            pair.client.parts.migrate(ANY, RttPrior.CONFIGURED, MOVE_NETWORK)
            pair.runUntil(within = 2.seconds) { pair.clientSockets == 2 }
            pair.rehome()
            pair.runFor(SETTLED)
            assertEquals(0, pair.secondReopens, "the second socket was reopened for a rehome of the carrying one")
            assertEquals(0, pair.carryingReopens, "the leaving carrying socket was reopened")
            assertIs<EdgeEnd.Open>(pair.client.connection.end())
        }

    /** A rehome read before a promotion, on the network the promoted socket is on, still tells the connection it can send. */
    @Test
    fun aStaleRehomeOnTheCarriedNetworkStillTellsUsable() =
        VirtualPair.with(options) { pair ->
            val first = pair.clientPort()
            val earlier =
                pair.client.parts.carried
                    .read()
            pair.client.parts.migrate(ANY, RttPrior.CONFIGURED, MOVE_NETWORK)
            pair.runUntil(within = 5.seconds) { pair.clientPort() != first && pair.clientSockets == 1 }
            pair.runFor(SETTLED)
            val usable = marks(pair).count { it == "Usable" }
            pair.client.parts.rehome(earlier.ticket, MOVE_NETWORK)
            pair.runFor(SETTLED)
            assertEquals(usable + 1, marks(pair).count { it == "Usable" }, "the connection was never told it can send")
            assertEquals(0, pair.carryingReopens)
        }

    /** The real feed of [pair]'s client, on the test's thread, over a scripted monitor and route. */
    private class Feed(
        private val pair: VirtualPair,
    ) {
        val monitor = Monitor()
        var route: RouteSource = FROM_HOME
        private var job: Job = Job()

        fun start(scope: CoroutineScope) {
            job =
                pair.client.scope.feedNetwork(
                    monitor,
                    ClientFeed(pair.client.connection, pair.client.parts),
                    { route },
                    OnMove.Migrate,
                    scope.coroutineContext.minusKey(Job),
                ) { host -> InetSocketAddress(host, 0).toPathKey() }
        }

        fun stop() = job.cancel()
    }

    private class Monitor : NetworkMonitor {
        override val state = MutableStateFlow<NetworkState>(NetworkState.Routable(WIFI, InternetAccess.Observed.Confirmed))

        override fun close() = Unit
    }

    private suspend fun CoroutineScope.marks(pair: VirtualPair): List<String> {
        val recorded = ArrayList<TraceEvent>()
        var done = false
        launch {
            pair.client.connection.dumpFlight(TraceSink { recorded += it })
            done = true
        }
        pair.runUntil { done }
        return recorded.filterIsInstance<TraceEvent.PathState>().map { it.phase }
    }

    private fun payload(): PlatformBuffer =
        BufferFactory.Default.allocate(PAYLOAD).apply {
            repeat(PAYLOAD) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        const val PAYLOAD = 600
        val SETTLED = 500.milliseconds
        val WIFI = NetworkId.Link(NetworkKind.Wifi, 1L)
        val CELLULAR = NetworkId.Link(NetworkKind.Cellular, 2L)
        val CELLULAR_AGAIN = NetworkId.Link(NetworkKind.Cellular, 9L)
        val FROM_HOME = RouteSource.Resolved(LOCALHOST)
        const val OTHER = "127.0.0.2"
        val FROM_OTHER = RouteSource.Resolved(OTHER)
        val OTHER_KEY: PathKey = InetSocketAddress(OTHER, 0).toPathKey()
        val ANY = PathKey(family = 4, port = 0, hi = 0L, lo = 0L)
    }
}
