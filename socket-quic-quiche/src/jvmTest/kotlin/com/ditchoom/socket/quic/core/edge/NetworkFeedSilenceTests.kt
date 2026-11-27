package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.core.conn.NetworkChange
import com.ditchoom.socket.quic.core.path.HomeCell
import com.ditchoom.socket.quic.core.path.PathNews
import com.ditchoom.socket.quic.core.path.PathNewsCell
import com.ditchoom.socket.quic.core.time.RttPrior
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

/**
 * The network feed between a connection's cells and a scripted monitor, on the test's own thread: what it asks of the
 * connection for each silence the connection declares, and for a monitor reading after an ask the connection did not
 * act on.
 */
class NetworkFeedSilenceTests {
    /**
     * Each declaration of silence is one rebind, an answering path none, and a declaration made while the feed was busy
     * counts too: Silent, Answering, Silent before the feed runs is a second silence, not the first one seen again.
     */
    @Test
    fun eachDeclaredSilenceIsOneRebindEvenWhileTheFeedIsBusy() =
        feeding { target ->
            target.news.tell(PathNews.Answering)
            settle()
            assertEquals(0, target.rebinds.size, "an answering path asked for a rebind")
            repeat(3) {
                target.news.tell(PathNews.Silent)
                settle()
                target.news.tell(PathNews.Answering)
                settle()
            }
            assertEquals(3, target.rebinds.size, "three silences with the feed free")
            target.news.tell(PathNews.Silent)
            target.news.tell(PathNews.Answering)
            target.news.tell(PathNews.Silent)
            settle()
            assertEquals(4, target.rebinds.size, "a silence declared while the feed was busy was lost")
            target.home.settled(WIFI_KEY)
            settle()
            assertEquals(4, target.rebinds.size, "a wake for something else asked again for the same silence")
        }

    /**
     * A rebind toward cellular's address that the connection never landed leaves the socket where it was: the monitor
     * then naming cellular, its route from that address, is a migration, never a reopen the platform would refuse.
     */
    @Test
    fun aReadingAfterARebindTheConnectionDidNotTakeMigratesAndNeverReopens() =
        feeding(route = RouteSource.Resolved(CELLULAR_ADDRESS)) { target ->
            target.news.tell(PathNews.Silent)
            settle()
            assertEquals(listOf(CELLULAR_KEY), target.rebinds)
            target.news.tell(PathNews.Answering)
            monitor.state.value = NetworkState.Routable(CELLULAR, InternetAccess.Unobserved)
            settle()
            assertEquals(listOf(CELLULAR_KEY), target.migrates, "the handoff was swallowed")
            assertEquals(0, target.rehomes, "the Wi-Fi socket was reopened on cellular")
        }

    /**
     * A roam: Wi-Fi is offline for a moment, the path is declared silent meanwhile, and the same network comes back.
     * The silence the watch could not act on while offline is asked about once the network is back: the connection
     * declares a run silent only once, so a silence dropped there would leave it on a dead 5-tuple until its idle end.
     */
    @Test
    fun aSilenceDeclaredWhileOfflineIsAskedAboutOnceTheNetworkIsBack() =
        feeding { target ->
            monitor.state.value = NetworkState.Offline
            settle()
            target.news.tell(PathNews.Silent)
            settle()
            assertEquals(0, target.rebinds.size, "a rebind asked with no network")
            monitor.state.value = NetworkState.Routable(WIFI, InternetAccess.Unobserved)
            settle()
            assertEquals(listOf(WIFI_KEY), target.rebinds, "the silence declared while offline was dropped")
            assertEquals(listOf(NetworkChange.Gone, NetworkChange.Usable), target.changes)
        }

    private class Monitor : NetworkMonitor {
        override val state = MutableStateFlow<NetworkState>(NetworkState.Routable(WIFI, InternetAccess.Unobserved))

        override fun close() = Unit
    }

    private class Target : FeedTarget {
        override val news = PathNewsCell()
        override val home = HomeCell(news.wake)
        val rebinds = ArrayList<PathKey>()
        val migrates = ArrayList<PathKey>()
        var rehomes = 0
        val changes = ArrayList<NetworkChange>()

        override fun network(change: NetworkChange) {
            changes += change
        }

        override fun migrate(
            bind: PathKey,
            prior: RttPrior,
        ) {
            migrates += bind
        }

        override fun rehome() {
            rehomes++
        }

        override fun rebind(
            bind: PathKey,
            prior: RttPrior,
        ) {
            rebinds += bind
        }
    }

    private class Feeding(
        val monitor: Monitor,
    ) {
        suspend fun settle() = repeat(YIELDS) { yield() }
    }

    private fun feeding(
        route: RouteSource = RouteSource.Resolved(WIFI_ADDRESS),
        body: suspend Feeding.(Target) -> Unit,
    ) = runBlocking {
        val monitor = Monitor()
        val target = Target()
        val scope = CoroutineScope(coroutineContext.minusKey(Job))
        scope.feedNetwork(monitor, target, { route }, WIFI_ADDRESS, OnMove.Migrate, coroutineContext.minusKey(Job)) { host ->
            if (host == CELLULAR_ADDRESS) CELLULAR_KEY else WIFI_KEY
        }
        try {
            val feeding = Feeding(monitor)
            feeding.settle()
            feeding.body(target)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val YIELDS = 20
        const val WIFI_ADDRESS = "192.0.2.10"
        const val CELLULAR_ADDRESS = "198.51.100.20"
        val WIFI = NetworkId.Link(NetworkKind.Wifi, 1L)
        val CELLULAR = NetworkId.Link(NetworkKind.Cellular, 2L)
        val WIFI_KEY = PathKey(4, 0, 0L, 0xC000020AL)
        val CELLULAR_KEY = PathKey(4, 0, 0L, 0xC6336414L)
    }
}
