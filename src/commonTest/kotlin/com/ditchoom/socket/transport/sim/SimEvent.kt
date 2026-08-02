package com.ditchoom.socket.transport.sim

import com.ditchoom.socket.NetworkState
import kotlin.time.Duration
import com.ditchoom.socket.transport.Liveness as TransportLiveness

/**
 * Transport-layer copy of the W2 simulation event model — same shapes as
 * `com.ditchoom.socket.quic.sim.SimEvent` in `:socket-quic-quiche`'s commonTest, kept here because
 * the transport-layer golden fixture needs `ReconnectingConnection` from this module and the quiche
 * module's test source set is not visible from here.
 *
 * **PROMOTION CANDIDATE**: when the harness is published (RFC_DETERMINISTIC_SIMULATION.md §3.2 /
 * §8, `socket-testsuite`), this copy and the quiche one unify into that shared artifact. Only the
 * events with a transport-layer seam are carried; the packet-level events (`DatagramIn` /
 * `SendError` / `RecvError`) exist solely on the quiche engine's `UdpChannel` seam.
 */
internal sealed interface SimEvent {
    /** Virtual instant (offset from the timeline's t0) at which the interpreter injects this event. */
    val at: Duration

    /**
     * A scripted `NetworkMonitor.state` emission — the backoff-race trigger.
     *
     * One event, because the monitor now has one flow. The former `Availability`/`Network` pair could
     * script a state no monitor can produce (an unavailable network with a live identity), which is the
     * torn read RFC_NETWORK_REACHABILITY §1.2 removed from the contract; a fixture that could still
     * express it would keep testing behaviour against inputs reality cannot deliver.
     */
    data class Net(
        override val at: Duration,
        val state: NetworkState,
    ) : SimEvent

    /** Script the outcome of the **next** liveness probe (the #222 seam). */
    data class Liveness(
        override val at: Duration,
        val result: TransportLiveness.Result,
    ) : SimEvent
}
