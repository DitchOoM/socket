package com.ditchoom.socket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * JVM [NetworkMonitor] that polls [NetworkInterface.getNetworkInterfaces] plus a packet-free route probe
 * ([resolveJvmNetworkState]). Polling is the only portable option on JVM desktop below JDK 21 — no
 * event-driven network-change API exists in the JDK before FFM.
 *
 * Reports [ReachResolution.RouteOnly]: it distinguishes [NetworkState.LinkLocal] from
 * [NetworkState.Routable], and never observes internet reachability.
 *
 * Deliberately leaves [NetworkMonitor.observationCount] at the contract default (never advances): a
 * poll's cadence is this constructor's [interval], not a property of the network, so reporting it as
 * observation density would be a configuration constant dressed up as a measurement.
 *
 * @param interval How often to re-resolve the network state (default 5 seconds).
 * @param checkNetwork Injectable resolver, for testing without touching real interfaces.
 */
class PollingNetworkMonitor(
    private val interval: Duration = 5.seconds,
    private val checkNetwork: () -> NetworkState = ::resolveJvmNetworkState,
) : NetworkMonitor {
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Unknown)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    override val capability: MonitorCapability =
        MonitorCapability(MonitorMechanism.Polled(interval), ReachResolution.RouteOnly)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            while (isActive) {
                _state.value = checkNetwork()
                delay(interval)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}

/**
 * Creates a JVM [NetworkMonitor] that polls network interfaces.
 *
 * @param interval How often to check (default 5 seconds).
 */
fun NetworkMonitor.Companion.polling(interval: Duration = 5.seconds): NetworkMonitor = PollingNetworkMonitor(interval)
