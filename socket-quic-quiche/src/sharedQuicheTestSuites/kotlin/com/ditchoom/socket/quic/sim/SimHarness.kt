package com.ditchoom.socket.quic.sim

import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.MonitorCapability
import com.ditchoom.socket.MonitorMechanism
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.ReachResolution
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.ditchoom.socket.transport.Liveness as TransportLiveness

/**
 * The seams one simulation run injects events through, bundled for [SimTimeline]'s interpreter.
 * Built per run by `runQuicSim`; the [trace] collects the observations the same run emits.
 */
internal class SimHarness(
    val udp: TimelineUdpChannel,
    val monitor: SimNetworkMonitor,
    val liveness: SimLiveness,
    val clock: SimClock,
    val trace: SimTrace,
)

/**
 * Settable [NetworkMonitor] for timeline scripting — a copy of the root module's test
 * `MockNetworkMonitor` (root's copy stays untouched; promotion into a published harness artifact
 * is RFC_DETERMINISTIC_SIMULATION.md §3.2's plan, deliberately not this wave).
 */
internal class SimNetworkMonitor(
    initial: NetworkState = NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Unobserved),
    override val capability: MonitorCapability =
        MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteAndInternet),
) : NetworkMonitor {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    /** Publish an arbitrary state. */
    fun set(value: NetworkState) {
        _state.value = value
    }

    /**
     * Swap identity, keeping the current rung — "the platform handed us a different link", which is
     * exactly the auto-migration trigger. From a rung with no link, a link appearing is
     * `Routable(id, Unobserved)`.
     */
    fun setNetworkId(value: NetworkId) {
        _state.value =
            when (val current = _state.value) {
                is NetworkState.LinkLocal -> NetworkState.LinkLocal(value)
                is NetworkState.Routable -> current.copy(id = value)
                NetworkState.Offline, NetworkState.Unknown ->
                    NetworkState.Routable(value, InternetAccess.Unobserved)
            }
    }

    override fun close() {}

    companion object {
        /**
         * A monitor already routable on [id] — the common "connected on this link" start.
         *
         * A named factory rather than a second constructor parameter beside `initial`: two parameters
         * that both describe the starting value can disagree, and a caller passing both would have had
         * one silently ignored. One value in, no impossible combination.
         */
        fun on(id: NetworkId): SimNetworkMonitor = SimNetworkMonitor(NetworkState.Routable(id, InternetAccess.Unobserved))
    }
}

/**
 * Scripted [TransportLiveness] (#222 seam): [SimEvent.Liveness] enqueues the outcome the **next**
 * probe reports; an unscripted probe reports [TransportLiveness.Result.Unknown] (the seam's
 * no-teardown default). Every probe is recorded as [Observed.LivenessProbed].
 */
internal class SimLiveness(
    private val trace: SimTrace,
) : TransportLiveness {
    private val scripted = ArrayDeque<TransportLiveness.Result>()

    fun script(result: TransportLiveness.Result) {
        scripted.addLast(result)
    }

    override suspend fun probe(): TransportLiveness.Result {
        val result = scripted.removeFirstOrNull() ?: TransportLiveness.Result.Unknown
        trace.record(Observed.LivenessProbed(trace.now(), result))
        return result
    }
}
