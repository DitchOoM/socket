package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.LinkAddresses
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.NumericAddress
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicPathStats
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.edge.FeedTarget
import com.ditchoom.socket.quic.core.edge.OnMove
import com.ditchoom.socket.quic.core.edge.feedNetwork
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.path.MoveWanted
import com.ditchoom.socket.quic.core.path.PathNews
import com.ditchoom.socket.quic.core.path.PathNewsCell
import com.ditchoom.socket.quic.core.path.Silence
import com.ditchoom.socket.quic.core.path.SilenceRule
import com.ditchoom.socket.quic.core.path.SocketAsk
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.time.Span
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The network feed and a client connection together: the feed on the test's thread, the connection on a [ConnRig]
 * over [StubQuicheApi], so the edge hears only what the connection itself says. A peer that forbids a move (as
 * Cloudflare's does) answers every rebind with nothing.
 */
class RebindFeedTests {
    /**
     * The route already leaves from cellular's address while the monitor still names Wi-Fi; the path goes silent and the
     * rebind toward that address is refused by the peer. The connection says so, so the next reading of the same
     * network with the route from there is still asked as a move, and nothing reopens the socket or ends the connection.
     */
    @Test
    fun aRebindThePeerForbidsLeavesTheNextReadingAMoveAndTheConnectionOpen(): Unit =
        runBlocking<Unit> {
            val rig = Rig()
            val scope = CoroutineScope(coroutineContext.minusKey(Job))
            scope.feedNetwork(rig.monitor, rig.target, { rig.route }, OnMove.Migrate, coroutineContext.minusKey(Job)) {
                if (it == CELLULAR_ADDRESS) CELLULAR_KEY else WIFI_KEY
            }
            try {
                rig.settle()
                rig.route = RouteSource.Resolved(CELLULAR_ADDRESS)
                rig.goSilent()
                rig.settle()
                assertTrue("RebindForbidden" in rig.marks(), "precondition: the rebind was asked and forbidden: ${rig.marks()}")
                // The same Wi-Fi, read again: its links' addresses changed.
                rig.monitor.linkAddresses.value = LinkAddresses.Reported(mapOf(WIFI to setOf(NumericAddress.V4(1))))
                rig.settle()
                assertEquals(listOf(CELLULAR_KEY), rig.target.migrates, "the handoff was swallowed after the forbidden rebind")
                assertEquals(0, rig.target.rehomes, "the Wi-Fi socket was reopened")
                assertIs<Life.Running>(rig.rig.conn.life(), "the connection did not stay where it was")
            } finally {
                scope.cancel()
            }
        }

    private class Monitor : NetworkMonitor {
        override val state = MutableStateFlow<NetworkState>(NetworkState.Routable(WIFI, InternetAccess.Unobserved))
        override val linkAddresses = MutableStateFlow<LinkAddresses>(LinkAddresses.NotReported)

        override fun close() = Unit
    }

    /** The edge's side of the connection: everything it asks goes to the rig's connection as the core's parts send it. */
    private class Target(
        override val news: PathNewsCell,
        override val carried: CarriedCell,
        private val rig: () -> ConnRig,
    ) : FeedTarget {
        val migrates = ArrayList<PathKey>()
        var rehomes = 0

        override fun network(change: NetworkChange) = rig().conn.submit(NetworkWork(change))

        override fun migrate(
            bind: PathKey,
            prior: RttPrior,
            network: NetworkId,
            named: NetworkId,
        ) {
            migrates += bind
            rig().conn.submit(MoveWantedWork(MoveWanted(bind, prior, network, named)))
        }

        override fun rehome(
            ticket: SocketTicket,
            network: NetworkId,
        ) {
            rehomes++
        }

        override fun rebind(
            bind: PathKey,
            prior: RttPrior,
            network: NetworkId,
        ) = rig().conn.submit(RebindWork(MoveWanted(bind, prior, network)))
    }

    private class Rig {
        val monitor = Monitor()
        var route: RouteSource = RouteSource.Resolved(WIFI_ADDRESS)
        val api =
            StubQuicheApi().apply {
                availableDcids = 4L
                peerDisablesActiveMigration = true
            }
        private val news = PathNewsCell()

        // No loop runs here: the socket the connect opened carries throughout.
        private val carried = CarriedCell(news.wake, Carried(SocketTicket.FIRST, WIFI_KEY, WIFI))
        val asks = ArrayList<SocketAsk>()
        val rig = ConnRig(api, silence = SilenceRule(Silence.On, Span(30 * SECOND), news), ask = { asks += it })
        val target = Target(news, carried) { rig }
        var now = SECOND
        private var probes = 0L

        init {
            api.pathStats = listOf(stats(0L))
            api.connTimeout = TICK.nanoseconds
            rig.pass(now)
        }

        fun goSilent() {
            var ticks = 0
            while (news.read() != PathNews.Silent && ticks++ < 100) {
                api.pathStats = listOf(stats(++probes))
                api.connTimeout = Duration.ZERO
                now += TICK
                rig.pass(now)
            }
            api.connTimeout = null
            assertEquals(PathNews.Silent, news.read(), "the rig's path never went silent")
        }

        /** The feed and the connection take turns until both are quiet. */
        suspend fun settle() =
            repeat(ROUNDS) {
                repeat(YIELDS) { yield() }
                now += 1
                rig.pass(now)
            }

        fun marks(): List<String> {
            val recorded = ArrayList<com.ditchoom.socket.testkit.trace.TraceEvent>()
            rig.conn.submit(
                object : ConnWork() {
                    override fun run(inside: Inside) = inside.dump { recorded += it }

                    override fun gone(end: com.ditchoom.socket.quic.core.life.ConnEnd) = Unit
                },
            )
            rig.pass(now)
            return recorded.filterIsInstance<com.ditchoom.socket.testkit.trace.TraceEvent.PathState>().map { it.phase }
        }
    }

    private companion object {
        const val SECOND = 1_000_000_000L
        val TICK = 100.milliseconds.inWholeNanoseconds
        const val ROUNDS = 10
        const val YIELDS = 10
        const val WIFI_ADDRESS = "192.0.2.10"
        const val CELLULAR_ADDRESS = "198.51.100.20"
        val WIFI = NetworkId.Link(NetworkKind.Wifi, 1L)
        val WIFI_KEY = PathKey(4, 0, 0L, 0xC000020AL)
        val CELLULAR_KEY = PathKey(4, 0, 0L, 0xC6336414L)

        fun stats(probes: Long) =
            QuicPathStats(
                validationState = 0,
                active = true,
                recv = 0,
                sent = 0,
                lost = 0,
                retrans = 0,
                totalPtoCount = probes,
                rtt = Duration.ZERO,
                minRtt = Duration.ZERO,
                maxRtt = Duration.ZERO,
                rttvar = Duration.ZERO,
                cwnd = 0,
                sentBytes = 0,
                recvBytes = 0,
                lostBytes = 0,
                streamRetransBytes = 0,
                pmtu = 0,
                deliveryRate = 0,
            )
    }
}
