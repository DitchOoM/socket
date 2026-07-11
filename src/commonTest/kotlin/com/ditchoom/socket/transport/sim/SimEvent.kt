package com.ditchoom.socket.transport.sim

import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.transport.NetworkId
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

    /** A scripted `NetworkMonitor.availability` emission. */
    data class Availability(
        override val at: Duration,
        val value: NetworkAvailability,
    ) : SimEvent

    /** A scripted `NetworkMonitor.networkId` emission — the backoff-race trigger. */
    data class Network(
        override val at: Duration,
        val id: NetworkId,
    ) : SimEvent

    /** Script the outcome of the **next** liveness probe (the #222 seam). */
    data class Liveness(
        override val at: Duration,
        val result: TransportLiveness.Result,
    ) : SimEvent
}
