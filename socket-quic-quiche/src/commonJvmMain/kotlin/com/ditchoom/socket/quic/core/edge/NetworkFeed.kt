@file:OptIn(com.ditchoom.socket.quic.InternalQuicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.networkId
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.core.conn.NetworkChange
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Feeds the network monitor's news into [connection], off the loop, as [HomeWatch] judges it. Every change of the
 * monitor's state or of the link addresses it reports is read again, with the route to the peer asked afresh: the
 * same address on another network reopens the socket there ([ClientParts.rehome]) and the connection sends from it in
 * that pass; a usable network back on the same one has it send at once; a route from another address ends it (that
 * is migration); no usable network is ridden out, bounded by the idle timeout. It runs on [context]: an IO thread, or
 * the thread of a test that drives the loop on virtual time.
 */
internal fun CoroutineScope.feedNetwork(
    monitor: NetworkMonitor,
    connection: CoreConnection,
    parts: ClientParts,
    routeSource: suspend () -> RouteSource,
    boundHost: String,
    context: CoroutineContext = Dispatchers.IO,
): Job =
    launch(context + CoroutineName("quic-core/network")) {
        val watch = HomeWatch(boundHost, monitor.state.value.networkId)
        combine(monitor.state, monitor.linkAddresses) { state, _ -> state }.drop(1).collect { now ->
            val step =
                when (now) {
                    is NetworkState.Unknown -> HomeStep.Stay
                    is NetworkState.Offline -> watch.offline()
                    is NetworkState.Up -> watch.up(now.id, routeSource())
                }
            when (step) {
                HomeStep.Stay -> Unit
                HomeStep.Usable -> connection.network(NetworkChange.Usable)
                HomeStep.Gone -> connection.network(NetworkChange.Gone)
                HomeStep.Moved -> connection.network(NetworkChange.Moved)
                HomeStep.Rehome -> parts.rehome()
            }
        }
    }
