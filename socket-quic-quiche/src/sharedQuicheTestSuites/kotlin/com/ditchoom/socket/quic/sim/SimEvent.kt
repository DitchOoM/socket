package com.ditchoom.socket.quic.sim

import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.PathKey
import kotlin.time.Duration
import com.ditchoom.socket.transport.Liveness as TransportLiveness

/**
 * One scheduled **input** of a simulation timeline (RFC_DETERMINISTIC_SIMULATION.md §2, W2).
 *
 * A timeline is an ordered list of these, each stamped with the virtual instant [at] (offset from
 * the timeline's t0) at which [SimTimeline] injects it through the matching deterministic seam:
 *
 * | Event | Seam |
 * |---|---|
 * | [DatagramIn]    | [TimelineUdpChannel] (the driver's `UdpChannel`) |
 * | [SendError]     | [TimelineUdpChannel] — thrown from the next `send()` |
 * | [RecvError]     | [TimelineUdpChannel] — surfaces from the (parked) `receive()` |
 * | [Net]           | [SimNetworkMonitor.set] |
 * | [Liveness]      | [SimLiveness] scripted probe queue |
 *
 * `ClockAdvance` is implicit: the interpreter advances the virtual clock between events, so a
 * multi-minute recorded saga replays in milliseconds of wall-clock.
 *
 * Payloads are hex strings — the embedded-hex corpus pattern (portable to every platform, no
 * runtime file IO), same as the committed H3/QPACK fuzz corpora.
 */
internal sealed interface SimEvent {
    /** Virtual instant (offset from the timeline's t0) at which the interpreter injects this event. */
    val at: Duration

    /**
     * A UDP datagram arriving from the network. [from] is carried for future multi-path scripting
     * (a migrating connection has one reader loop per path); the single-path [TimelineUdpChannel]
     * ignores it — the driver's primary reader loop tags packets with the primary [PathKey] itself.
     */
    data class DatagramIn(
        override val at: Duration,
        val payloadHex: String,
        val from: PathKey? = null,
    ) : SimEvent

    /** Arm a typed I/O fault thrown from the **next** [TimelineUdpChannel] `send()`. */
    data class SendError(
        override val at: Duration,
        val error: SimError,
    ) : SimEvent

    /** A typed I/O fault surfaced from the (parked) [TimelineUdpChannel] `receive()` at this instant. */
    data class RecvError(
        override val at: Duration,
        val error: SimError,
    ) : SimEvent

    /**
     * A scripted `NetworkMonitor.state` emission — the migration/reconnect-race trigger (#222 shape).
     *
     * One event, because the monitor has one flow. The former `Availability`/`Network` pair could
     * script an incoherent pair (an unavailable network with a live identity) that no real monitor can
     * produce — the torn read RFC_NETWORK_REACHABILITY §1.2 removed from the contract.
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

/**
 * A typed I/O fault a timeline injects at the `UdpChannel` seam (ENETDOWN, port-unreachable, a
 * closed channel mid-shutdown, …). Deliberately minimal in W2 — the driver's `flushOutgoing` /
 * `udpReaderLoop` react to the exception *type-agnostically* (any non-cancellation failure), so the
 * fault's identity only needs to survive into the trace/fixture, not model platform errno taxonomies.
 */
internal data class SimError(
    val message: String,
)

/** The exception [TimelineUdpChannel] throws to surface an injected [SimError] to the driver. */
internal class SimIoException(
    val error: SimError,
) : Exception(error.message)
