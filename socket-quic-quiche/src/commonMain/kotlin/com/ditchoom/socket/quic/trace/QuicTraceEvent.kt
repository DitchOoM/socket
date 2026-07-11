package com.ditchoom.socket.quic.trace

import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicPathStats
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.Liveness as TransportLiveness

/**
 * One recorded trace event — the typed form of a `v1` trace line (grammar: [QuicTraceRecorder]).
 *
 * Two roles, mirroring RFC_DETERMINISTIC_SIMULATION.md §2:
 *  - **Input events** ([DgramIn], [Error], [NetAvail], [Net], [Liveness]) are replayable — the
 *    fixture codegen turns them into a `simFixture` timeline injected through the deterministic
 *    seams, and `parse(emit(e)) == e` holds exactly (the round-trip bar).
 *  - **Observations** ([DgramOut], [State], [PathState], [Stats]) are the golden trajectory —
 *    never injected, only compared. [State]/[PathState] deliberately carry the *rendered* state
 *    (name + detail strings), not the sealed `QuicConnectionState`, because replay asserts them
 *    structurally (Tier A: a stub cannot re-decrypt field ciphertext, so byte-exact state equality
 *    is not the contract — trajectory shape is).
 *
 * All variants are value types with structural equality; timestamps are nanoseconds from the
 * recorder's single [com.ditchoom.socket.quic.DriverClock] origin.
 */
sealed interface QuicTraceEvent {
    /** Nanoseconds since the recorder's clock origin (one clock per RFC §5). */
    val atNanos: Long

    /** The driver sent a UDP datagram at the `UdpChannel` seam. Observation. */
    data class DgramOut(
        override val atNanos: Long,
        val len: Int,
        val path: PathKey?,
        val payloadHex: String,
    ) : QuicTraceEvent

    /** A UDP datagram arrived at the `UdpChannel` seam. Input event (replay: `datagramIn`). */
    data class DgramIn(
        override val atNanos: Long,
        val len: Int,
        val path: PathKey?,
        val payloadHex: String,
    ) : QuicTraceEvent

    /**
     * A `QuicConnectionState` transition. [name] is the state's class name (`Handshaking`,
     * `Established`, `Closed`, …); [detail] carries the state's payload rendered one-line
     * (negotiated ALPN, typed close reason via `QuicError.describe()`), or `null`. Observation.
     */
    data class State(
        override val atNanos: Long,
        val name: String,
        val detail: String?,
    ) : QuicTraceEvent

    /** A `PathInfo` (migration) transition: [phase] is the `MigrationPhase` name. Observation. */
    data class PathState(
        override val atNanos: Long,
        val phase: String,
        val localHost: String?,
        val localPort: Int,
    ) : QuicTraceEvent

    /**
     * A typed error surfaced at a recorded choke point ([type] = the throwable's or `QuicError`'s
     * class name — errors stay typed, never bare strings; [message] is diagnostic detail, newlines
     * flattened to spaces). Input event (replay: injected socket fault).
     */
    data class Error(
        override val atNanos: Long,
        val type: String,
        val message: String,
    ) : QuicTraceEvent

    /** A quiche path-stats snapshot ([QuicPathStats]), polled on the driver's timer wake. Observation. */
    data class Stats(
        override val atNanos: Long,
        val stats: QuicPathStats,
    ) : QuicTraceEvent

    /** A `NetworkMonitor.availability` emission. Input event. */
    data class NetAvail(
        override val atNanos: Long,
        val value: NetworkAvailability,
    ) : QuicTraceEvent

    /** A `NetworkMonitor.networkId` emission. Input event. */
    data class Net(
        override val atNanos: Long,
        val id: NetworkId,
    ) : QuicTraceEvent

    /** A liveness probe outcome (via [QuicTraceRecorder.wrap]). Input event. */
    data class Liveness(
        override val atNanos: Long,
        val result: TransportLiveness.Result,
    ) : QuicTraceEvent

    /** True for the replayable input-event subset (RFC §2), false for observations. */
    val isInput: Boolean
        get() =
            when (this) {
                is DgramIn, is Error, is NetAvail, is Net, is Liveness -> true
                is DgramOut, is State, is PathState, is Stats -> false
            }
}
