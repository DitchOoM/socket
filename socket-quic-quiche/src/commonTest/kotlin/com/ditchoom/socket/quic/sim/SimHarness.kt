package com.ditchoom.socket.quic.sim

import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.NetworkMonitor
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
    initial: NetworkAvailability = NetworkAvailability.AVAILABLE,
    initialNetworkId: NetworkId = NetworkId.Unidentified,
) : NetworkMonitor {
    private val _availability = MutableStateFlow(initial)
    override val availability: StateFlow<NetworkAvailability> = _availability.asStateFlow()

    private val _networkId = MutableStateFlow(initialNetworkId)
    override val networkId: StateFlow<NetworkId> = _networkId.asStateFlow()

    fun set(value: NetworkAvailability) {
        _availability.value = value
    }

    fun setNetworkId(value: NetworkId) {
        _networkId.value = value
    }

    override fun close() {}
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
