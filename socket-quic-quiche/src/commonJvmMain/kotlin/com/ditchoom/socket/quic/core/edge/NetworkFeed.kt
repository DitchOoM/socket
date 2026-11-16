@file:OptIn(com.ditchoom.socket.quic.InternalQuicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.UdpSocketChannelFactory
import com.ditchoom.socket.quic.core.conn.NetworkChange
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** What one monitor transition means for a connection. */
private enum class Transition { Unchanged, Usable, Changed, Gone }

/**
 * Feeds the network monitor's news into [connection] as connection work, off the loop: a usable network that appears
 * or changes has the connection send at once, but only from the address its socket is bound to (a usable network
 * without that address is migration, and ends the connection now); no usable network starts the connection's grace.
 */
internal fun CoroutineScope.feedNetwork(
    monitor: NetworkMonitor,
    connection: CoreConnection,
    route: UdpSocketChannelFactory,
    boundHost: String,
): Job =
    launch(Dispatchers.IO + CoroutineName("quic-core/network")) {
        var previous = monitor.state.value
        monitor.state.drop(1).collect { now ->
            val transition = transitionOf(previous, now)
            previous = now
            when (transition) {
                Transition.Unchanged -> Unit
                Transition.Gone -> connection.network(NetworkChange.Gone)
                Transition.Usable -> connection.network(fromBound(route, boundHost, NetworkChange.Usable))
                Transition.Changed -> connection.network(fromBound(route, boundHost, NetworkChange.Changed))
            }
        }
    }

/** [change] when the route to the peer still leaves from [boundHost]; otherwise the connection has moved. */
private suspend fun fromBound(
    route: UdpSocketChannelFactory,
    boundHost: String,
    change: NetworkChange,
): NetworkChange =
    when (val source = route.routeSourceAddress()) {
        is RouteSource.Resolved -> if (source.host == boundHost) change else NetworkChange.Moved
        is RouteSource.PlatformAssigned -> change
        is RouteSource.Unresolved -> NetworkChange.Gone
    }

private fun transitionOf(
    previous: NetworkState,
    now: NetworkState,
): Transition =
    when (now) {
        is NetworkState.Offline -> Transition.Gone
        is NetworkState.Unknown -> Transition.Unchanged
        is NetworkState.Up ->
            when (previous) {
                is NetworkState.Up -> if (previous.id == now.id) Transition.Unchanged else Transition.Changed
                is NetworkState.Offline, is NetworkState.Unknown -> Transition.Usable
            }
    }
