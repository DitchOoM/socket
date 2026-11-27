@file:OptIn(com.ditchoom.socket.quic.InternalQuicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.LinkAddresses
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.networkId
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.core.conn.Carried
import com.ditchoom.socket.quic.core.conn.CarriedCell
import com.ditchoom.socket.quic.core.conn.HandshakeRule
import com.ditchoom.socket.quic.core.conn.NetworkChange
import com.ditchoom.socket.quic.core.path.PathNews
import com.ditchoom.socket.quic.core.path.PathNewsCell
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.toPathKey
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** What a client's network feed acts on: the connection it tells, and the cells it reads. */
internal interface FeedTarget {
    /** What the connection's silence watch says of its path. */
    val news: PathNewsCell

    /** Which socket carries the connection, as its loop publishes it. */
    val carried: CarriedCell

    fun network(change: NetworkChange)

    fun migrate(
        bind: PathKey,
        prior: RttPrior,
        network: NetworkId,
    )

    fun rehome(
        ticket: SocketTicket,
        network: NetworkId,
    )

    fun rebind(
        bind: PathKey,
        prior: RttPrior,
        network: NetworkId,
    )
}

/** A core client's [FeedTarget]: its network news goes through the [connection], its moves through its [parts]. */
internal class ClientFeed(
    private val connection: CoreConnection,
    private val parts: ClientParts,
) : FeedTarget {
    override val news: PathNewsCell get() = parts.news
    override val carried: CarriedCell get() = parts.carried

    override fun network(change: NetworkChange) = connection.network(change)

    // The feed only rides owned sockets, so the answer is always Asked; a lent ride has no feed.
    override fun migrate(
        bind: PathKey,
        prior: RttPrior,
        network: NetworkId,
    ) {
        parts.migrate(bind, prior, network)
    }

    override fun rehome(
        ticket: SocketTicket,
        network: NetworkId,
    ) = parts.rehome(ticket, network)

    override fun rebind(
        bind: PathKey,
        prior: RttPrior,
        network: NetworkId,
    ) = parts.rebind(bind, prior, network)
}

/**
 * Feeds the network monitor's news, and the connection's own news of its path, into [target], off the loop, as
 * [HomeWatch] judges them. Every change of the monitor's state or of the link addresses it reports is read again, with
 * the route to the peer asked afresh: the same address on another network reopens the socket there
 * ([FeedTarget.rehome]) and the connection sends from it in that pass; a usable network back on the same one has it
 * send at once; a route from another address asks the connection to move there ([FeedTarget.migrate]), or ends it
 * under [OnMove.End]; no usable network is ridden out, bounded by the idle timeout. Each time the connection declares
 * its path silent it asks for a fresh port on the route's address ([FeedTarget.rebind]), however many declarations
 * came while the feed was busy, and the connection takes it only if the path is still silent when the ask arrives.
 * Where the socket is, the feed reads at each input from what its loop publishes ([FeedTarget.carried]), which wakes it
 * at each change. It runs on [context]: the [setupLane], as its route probes and socket moves block, or the thread of a
 * test that drives the loop on virtual time, which also reads the address to move to without leaving that thread
 * ([bindKey]).
 */
internal fun CoroutineScope.feedNetwork(
    monitor: NetworkMonitor,
    target: FeedTarget,
    routeSource: suspend () -> RouteSource,
    onMove: OnMove,
    context: CoroutineContext = setupLane,
    bindKey: suspend (String) -> PathKey = { host -> UdpSocket.resolve(host, 0).toPathKey() },
): Job {
    // Read before the feed starts: what changes while it waits for its context is news, not where the socket opened.
    val opened = MonitorReading(monitor.state.value, monitor.linkAddresses.value)
    return launch(context + CoroutineName("quic-core/network")) {
        val watch = HomeWatch(onMove)
        var judged = opened
        var silences = 0L
        val readings = combine(monitor.state, monitor.linkAddresses, ::MonitorReading).dropWhile { it == opened }
        val woken =
            flow {
                while (true) {
                    val wake = target.news.wake
                    wake.pending.receive()
                    emit(FeedInput.Woken)
                }
            }
        merge(readings.map(FeedInput::Reading), woken).collect { input ->
            when (input) {
                is FeedInput.Reading -> {
                    judged = input.reading
                    when (val state = judged.state) {
                        is NetworkState.Unknown -> Unit
                        is NetworkState.Offline -> if (watch.offline() == HomeStep.Gone) target.network(NetworkChange.Gone)
                        is NetworkState.Up -> {
                            val now = Judged(judged, routeSource(), target.carried.read())
                            target.act(watch.up(state.id, now.source, now.carried), now, bindKey)
                        }
                    }
                }
                is FeedInput.Woken -> Unit
            }
            // A silence the watch could not act on (no network, no route) is asked about again with each input, until
            // the network it waits for is back or the path answers: the connection declares a run silent only once.
            val told = target.news.told()
            if (told.news is PathNews.Silent && told.silences != silences) {
                val now = Judged(judged, routeSource(), target.carried.read())
                val step = watch.silent(now.source)
                if (step == HomeStep.Rebind) silences = told.silences
                target.act(step, now, bindKey)
            }
        }
    }
}

/** One reading of the monitor, the route to the peer asked for it, and the socket that carried as it was read. */
private class Judged(
    val reading: MonitorReading,
    val source: RouteSource,
    val carried: Carried,
)

/**
 * What [step] asks of the connection: a move toward the address [now]'s route leaves from, on the network that carries
 * it; a rehome of the socket that carried as [now] was read, on the network [now] names.
 */
private suspend fun FeedTarget.act(
    step: HomeStep,
    now: Judged,
    bindKey: suspend (String) -> PathKey,
) {
    val named = now.reading.state.networkId
    val prior = RttPrior(HandshakeRule.priorOf(linkClassOf(named)))
    when (step) {
        HomeStep.Stay -> Unit
        HomeStep.Usable -> network(NetworkChange.Usable)
        HomeStep.Gone -> network(NetworkChange.Gone)
        HomeStep.Moved -> network(NetworkChange.Moved)
        HomeStep.Migrate -> towardRoute(now, bindKey) { bind, network -> migrate(bind, prior, network) }
        HomeStep.Rehome -> rehome(now.carried.ticket, named)
        HomeStep.Rebind -> towardRoute(now, bindKey) { bind, network -> rebind(bind, prior, network) }
    }
}

/** Asks [ask] for a socket on the address [now]'s route leaves from, on the link the monitor says carries it. */
private suspend inline fun towardRoute(
    now: Judged,
    bindKey: suspend (String) -> PathKey,
    ask: (PathKey, NetworkId) -> Unit,
) {
    val host = towardOf(now.source, now.carried)
    ask(bindKey(host), homeOf(host, now.reading.links, now.reading.state.networkId))
}

/** What the feed reads next: the monitor, or the connection's cells once it is woken. */
private sealed interface FeedInput {
    class Reading(
        val reading: MonitorReading,
    ) : FeedInput

    data object Woken : FeedInput
}

/** One reading of a network monitor: its state and the addresses its links carry. */
private data class MonitorReading(
    val state: NetworkState,
    val links: LinkAddresses,
)
