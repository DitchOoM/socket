package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.LinkAddresses
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.NumericAddress
import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The network monitor a replayed client's feed reads: the script's OS events become its state, and a change of where
 * the route leaves from becomes a change of its link addresses, which has the feed ask the route again. The route
 * leaves from the replay's bound host while the script says [RouteFrom.Bound].
 */
internal class ScriptMonitor(
    start: NetUp,
) : NetworkMonitor {
    override val state = MutableStateFlow<NetworkState>(stateOf(start))
    override val linkAddresses = MutableStateFlow<LinkAddresses>(LinkAddresses.Reported(emptyMap()))
    private var route: RouteFrom = RouteFrom.Bound

    /** OS readings published so far. */
    var readings = 0L
        private set

    fun said(event: OsEvent) {
        readings++
        when (event) {
            is OsEvent.Offline -> state.value = NetworkState.Offline
            is OsEvent.Up -> state.value = stateOf(event.up)
            is OsEvent.Route -> {
                route = event.route
                linkAddresses.value = LinkAddresses.Reported(mapOf(NetworkId.Unidentified to setOf(NumericAddress.V4(readings.toInt()))))
            }
        }
    }

    fun routeSource(): RouteSource =
        when (val now = route) {
            RouteFrom.Bound -> RouteSource.Resolved(LOCALHOST)
            is RouteFrom.Other -> RouteSource.Resolved(now.address)
            RouteFrom.NoRoute -> RouteSource.Unresolved(RouteProbeFailure.SourceAddressUnknown)
        }

    override fun close() = Unit

    private companion object {
        fun stateOf(up: NetUp): NetworkState =
            NetworkState.Routable(
                NetworkId.Link(kindOf(up.network.kind), up.network.ordinal.toLong()),
                when (up.validation) {
                    Validation.Validated -> InternetAccess.Observed.Confirmed
                    Validation.Pending -> InternetAccess.Observed.Pending
                    Validation.Unobserved -> InternetAccess.Unobserved
                },
            )

        fun kindOf(kind: NetKind): NetworkKind =
            when (kind) {
                NetKind.Wifi -> NetworkKind.Wifi
                NetKind.Cellular -> NetworkKind.Cellular
                NetKind.Ethernet -> NetworkKind.Ethernet
                NetKind.Other -> NetworkKind.Other("replayed")
            }
    }
}

/** The replayed application's one timer, on the replay's virtual clock. */
internal class Sleeper {
    private sealed interface Sleep {
        data object Awake : Sleep

        class Until(
            val at: Long,
            val woken: CompletableDeferred<Unit>,
        ) : Sleep
    }

    private var sleep: Sleep = Sleep.Awake

    /** Suspends until the replay's clock reaches [at]. */
    suspend fun until(at: Long) {
        val woken = CompletableDeferred<Unit>()
        sleep = Sleep.Until(at, woken)
        woken.await()
    }

    fun due(): Long =
        when (val now = sleep) {
            Sleep.Awake -> Long.MAX_VALUE
            is Sleep.Until -> now.at
        }

    fun wake(now: Long) {
        val asleep = sleep
        if (asleep is Sleep.Until && asleep.at <= now) {
            sleep = Sleep.Awake
            asleep.woken.complete(Unit)
        }
    }
}
