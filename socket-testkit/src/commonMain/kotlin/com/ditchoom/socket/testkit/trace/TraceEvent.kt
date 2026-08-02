package com.ditchoom.socket.testkit.trace

import com.ditchoom.socket.MonitorCapability
import com.ditchoom.socket.NetworkState
import kotlin.time.Duration
import com.ditchoom.socket.transport.Liveness as TransportLiveness

/**
 * A network path's identity as it appears in a trace — the neutral projection of the quiche-side
 * `PathKey` (family / port / raw address bits). Defined here in the transport-neutral `:socket-testkit`
 * so the trace model stays free of the quiche backend: the recorder maps `PathKey → TracePath` at its
 * `UdpChannel` choke point, one module downstream. Byte order of [hi]/[lo] is unspecified (a path is only ever
 * compared, never reconstructed), mirroring the source type.
 */
data class TracePath(
    /** 4 (IPv4), 6 (IPv6), or 0 (unknown). */
    val family: Int,
    val port: Int,
    val hi: Long,
    val lo: Long,
)

/**
 * A per-path quiche stats snapshot as it appears in a trace — the neutral projection of the
 * quiche-side `QuicPathStats` (same 18 fields, same declaration order). Kept in `:socket-testkit` so
 * the trace model has no compile dependency on the quiche backend; the recorder maps
 * `QuicPathStats → TracePathStats` at its `stats()` choke point.
 */
data class TracePathStats(
    /** Raw `validation_state` (ssize_t) — quiche's path validation progress. */
    val validationState: Long,
    /** Whether this path is active. */
    val active: Boolean,
    /** QUIC packets received on this path. */
    val recv: Long,
    /** QUIC packets sent on this path. */
    val sent: Long,
    /** QUIC packets lost on this path. */
    val lost: Long,
    /** Sent QUIC packets with retransmitted data on this path. */
    val retrans: Long,
    /** Times the PTO (probe timeout) fired — the normalized loss-event metric. */
    val totalPtoCount: Long,
    /** Estimated round-trip time of the path. */
    val rtt: Duration,
    /** Minimum observed round-trip time. */
    val minRtt: Duration,
    /** Maximum observed round-trip time. */
    val maxRtt: Duration,
    /** Estimated round-trip time variation. */
    val rttvar: Duration,
    /** Congestion window, bytes. */
    val cwnd: Long,
    /** Bytes sent on this path. */
    val sentBytes: Long,
    /** Bytes received on this path. */
    val recvBytes: Long,
    /** Bytes lost on this path. */
    val lostBytes: Long,
    /** Stream bytes retransmitted on this path. */
    val streamRetransBytes: Long,
    /** Current PMTU for the path. */
    val pmtu: Long,
    /** Most recent delivery-rate estimate, bytes/s. */
    val deliveryRate: Long,
)

/**
 * One recorded trace event — the typed form of a `v1` trace line (grammar: `QuicTraceRecorder`).
 * [emit][TraceSink.emit] carries these directly; [toString] renders the `v1` line and the companion
 * [parse]/[parseAll] decode it back, so `parse(e.toString()) == e` holds for every variant.
 *
 * Two roles, mirroring RFC_DETERMINISTIC_SIMULATION.md §2:
 *  - **Input events** ([DgramIn], [Error], [Net], [NetCapability], [Liveness]) are replayable — the
 *    fixture codegen turns them into a `simFixture` timeline injected through the deterministic
 *    seams, and the round-trip bar holds exactly.
 *  - **Observations** ([DgramOut], [State], [PathState], [Stats]) are the golden trajectory —
 *    never injected, only compared. [State]/[PathState] deliberately carry the *rendered* state
 *    (name + detail strings), not the sealed `QuicConnectionState`, because replay asserts them
 *    structurally (Tier A: a stub cannot re-decrypt field ciphertext, so byte-exact state equality
 *    is not the contract — trajectory shape is).
 *
 * All variants are value types with structural equality; [at] is an **offset** from the recorder's
 * single `DriverClock` origin, not a wall-clock instant — the recorder's clock seam is `() -> Duration`
 * precisely so live capture and `runTest` virtual time are the same code path, and so a committed
 * fixture does not churn its timestamps on every re-record. [State.name]/[Error.type] carry
 * **qualified** class names (`::class.qualifiedName`), not `simpleName`, so an obfuscated
 * Android-release trace can be retraced against R8's `mapping.txt` — the crash-reporter model (verbose
 * FQNs on readable platforms are accepted; only Android release obfuscates).
 */
sealed interface TraceEvent {
    /**
     * Offset from the recorder's clock origin (one clock per RFC §5) — a [Duration], not an `Instant`,
     * because virtual time has no epoch. Rendered on the wire as whole nanoseconds.
     */
    val at: Duration

    /** The driver sent a UDP datagram at the `UdpChannel` seam. Observation. */
    data class DgramOut(
        override val at: Duration,
        val len: Int,
        val path: TracePath?,
        val payloadHex: String,
    ) : TraceEvent {
        override fun toString(): String = encodeTraceLine(this)
    }

    /** A UDP datagram arrived at the `UdpChannel` seam. Input event (replay: `datagramIn`). */
    data class DgramIn(
        override val at: Duration,
        val len: Int,
        val path: TracePath?,
        val payloadHex: String,
    ) : TraceEvent {
        override fun toString(): String = encodeTraceLine(this)
    }

    /**
     * A `QuicConnectionState` transition. [name] is the state's **qualified** class name
     * (`…QuicConnectionState.Handshaking`, `.Established`, `.Closed`, …); [detail] carries the
     * state's payload rendered one-line (negotiated ALPN, typed close reason via
     * `QuicError.describe()`), or `null`. Observation.
     */
    data class State(
        override val at: Duration,
        val name: String,
        val detail: String?,
    ) : TraceEvent {
        override fun toString(): String = encodeTraceLine(this)
    }

    /** A `PathInfo` (migration) transition: [phase] is the `MigrationPhase` name. Observation. */
    data class PathState(
        override val at: Duration,
        val phase: String,
        val localHost: String?,
        val localPort: Int,
    ) : TraceEvent {
        override fun toString(): String = encodeTraceLine(this)
    }

    /**
     * A typed error surfaced at a recorded choke point ([type] = the throwable's or `QuicError`'s
     * **qualified** class name — errors stay typed, never bare strings; [message] is diagnostic
     * detail, newlines flattened to spaces). Input event (replay: injected socket fault).
     */
    data class Error(
        override val at: Duration,
        val type: String,
        val message: String,
    ) : TraceEvent {
        override fun toString(): String = encodeTraceLine(this)
    }

    /** A quiche path-stats snapshot ([TracePathStats]), polled on the driver's timer wake. Observation. */
    data class Stats(
        override val at: Duration,
        val stats: TracePathStats,
    ) : TraceEvent {
        override fun toString(): String = encodeTraceLine(this)
    }

    /**
     * A `NetworkMonitor.state` emission — the whole [NetworkState], identity included. Input event.
     *
     * This one variant replaced the former `NetAvail` + `Net` pair, and that is what fixed the defect it
     * was recording. `NetworkMonitorRecorder.observe()` used to launch **one collector per flow**, so
     * two independently-stamped streams interleaved by scheduling rather than by time — the 2026-07-29
     * device capture emitted an earlier `NET_AVAIL` *after* a later `NET_ID`:
     * ```
     * v1 40948500 NET_ID    Link:Wifi:458672230413
     * v1 40845500 NET_AVAIL AVAILABLE                 <- earlier timestamp, later in the stream
     * ```
     * One flow means one collector, so the stream is monotonic by construction rather than by
     * discipline (RFC_NETWORK_REACHABILITY §1.2 / §7).
     */
    data class Net(
        override val at: Duration,
        val state: NetworkState,
    ) : TraceEvent {
        override fun toString(): String = encodeTraceLine(this)
    }

    /**
     * The recorded monitor's [MonitorCapability]. Input event, emitted **once**, at the instant the
     * recorder subscribes.
     *
     * Capability is constant for a monitor's lifetime, so it is not re-recorded — but it has to be in the
     * trace at all, because replay reconstructs a `ScriptedNetworkMonitor` that validates every scripted
     * state against its declared capability. Without this line a replayed fixture would have to be handed
     * a capability by the test author, who can get it wrong and silently weaken that check.
     */
    data class NetCapability(
        override val at: Duration,
        val capability: MonitorCapability,
    ) : TraceEvent {
        override fun toString(): String = encodeTraceLine(this)
    }

    /** A liveness probe outcome (via `QuicTraceRecorder.wrap`). Input event. */
    data class Liveness(
        override val at: Duration,
        val result: TransportLiveness.Result,
    ) : TraceEvent {
        override fun toString(): String = encodeTraceLine(this)
    }

    /** True for the replayable input-event subset (RFC §2), false for observations. */
    val isInput: Boolean
        get() =
            when (this) {
                is DgramIn, is Error, is Net, is NetCapability, is Liveness -> true
                is DgramOut, is State, is PathState, is Stats -> false
            }

    /**
     * Decodes `v1` trace lines back into typed [TraceEvent]s — the exact inverse of [toString],
     * kept next to it so the two can never drift. `parse(e.toString()) == e` for every variant; the
     * fixture codegen consumes the [isInput] subset.
     */
    companion object {
        /** Decode one line. Throws [IllegalArgumentException] on a malformed or unknown-version line. */
        fun parse(line: String): TraceEvent = decodeTraceLine(line)

        /** Decode a whole trace (one event per line; blank lines skipped), preserving order. */
        fun parseAll(lines: List<String>): List<TraceEvent> = lines.filter { it.isNotBlank() }.map { decodeTraceLine(it) }

        /** Decode a whole trace from newline-joined text. */
        fun parseAll(text: String): List<TraceEvent> = parseAll(text.lines())
    }
}
