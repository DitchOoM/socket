package com.ditchoom.socket.testkit.osnet

import com.ditchoom.socket.MonitorCapability
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.testkit.trace.encodeMechanism
import com.ditchoom.socket.testkit.trace.encodeResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration

/**
 * Records what the OS said about the device's network, as an `OS-NET` log line and a
 * [TraceEvent.OsNet] in the replay trace — one record per **change**.
 *
 * A **device**-level record, not a per-connection one. A walk runs one lane per target at once
 * ([WalkLane][com.ditchoom.socket.testkit.walk.WalkLane]), and the radio, the links and their
 * addresses are one fact about the phone that every lane shares: emitting it per lane would write N
 * copies that a later reader has to reconcile, and that could disagree the moment one lane sampled a
 * moment later than another. So it is written once, under
 * [WalkLane.RUN_TOKEN][com.ditchoom.socket.testkit.walk.WalkLane.Companion.RUN_TOKEN], and an
 * analyzer shares the run's lines into every lane exactly as it already does for `START`.
 *
 * The replay trace is per connection, so [seed] writes the current facts into a connection's own
 * trace as it opens: a fixture cut from the middle of a walk then carries the baseline it was in,
 * not only the changes that happened to fall inside its window.
 *
 * Changes are recorded as they happen where the platform signals them: every
 * [NetworkMonitor.observations] emission is a sample (including the ones
 * [NetworkMonitor.state][NetworkMonitor.state] dedupes away), and a caller with a platform telephony
 * callback calls [sample] from it. [OsNetworkSource.cellularSignal] declares which of those a given
 * device gets, and is written into the run's `OS-NET-SOURCE` line, so a quiet stretch of log is
 * readable as "nothing changed" or "nothing was looked at" rather than guessed at.
 *
 * Lock-free: the observation collector, the heartbeat and a platform callback all sample
 * concurrently, and the compare-and-set below is what makes exactly one of them the writer of any
 * given transition.
 */
public class OsNetWatch(
    private val source: OsNetworkSource,
    private val clock: () -> Duration,
    private val emit: (String) -> Unit,
    private val sink: TraceSink,
) {
    /**
     * What has been recorded so far. Sealed, so "nothing yet" is a case rather than a null that
     * every reader has to be told the meaning of — and so [seed] cannot write a fabricated baseline
     * into a connection that opened before the first sample landed.
     */
    private sealed interface Recorded {
        public data object Nothing : Recorded

        public data class Last(
            val facts: OsNetworkFacts,
        ) : Recorded
    }

    private val recorded = MutableStateFlow<Recorded>(Recorded.Nothing)

    /**
     * The run's one-line statement of what this device can be asked — the monitor's declared ceiling
     * and how the cellular half learns of a change. Emitted once, before any `OS-NET` line.
     */
    public fun declare(capability: MonitorCapability) {
        emit(
            "OS-NET-SOURCE monitor=${encodeMechanism(capability.mechanism)}/${encodeResolution(capability.resolution)} " +
                "cellular=${source.cellularSignal.token}",
        )
    }

    /** Read the OS now, and record it if anything moved. Sampling an unchanged network writes nothing. */
    public fun sample(state: NetworkState) {
        val reading = source.read()
        val facts = OsNetworkFacts(state, reading.cellular, reading.links)
        while (true) {
            val previous = recorded.value
            if (previous is Recorded.Last && previous.facts == facts) return
            if (recorded.compareAndSet(previous, Recorded.Last(facts))) {
                emit("OS-NET ${facts.line}")
                sink.emit(TraceEvent.OsNet(clock(), facts))
                return
            }
        }
    }

    /**
     * Write the facts as they stand into [connection]'s own trace, so a fixture cut from this
     * connection starts from the network the device was actually on. A no-op before the first
     * [sample] — there is nothing recorded to seed with, and inventing one would be a fabricated
     * baseline.
     */
    public fun seed(connection: TraceSink) {
        when (val current = recorded.value) {
            Recorded.Nothing -> Unit
            is Recorded.Last -> connection.emit(TraceEvent.OsNet(clock(), current.facts))
        }
    }

    /**
     * Declare the source, then sample on every observation [monitor] publishes, for as long as the
     * caller's scope lives.
     *
     * [NetworkMonitor.observations] rather than [NetworkMonitor.state]: a link flapping hard while
     * every evaluation folds back to the same rung emits nothing on `state`, and that is precisely
     * when the OS has most to say.
     */
    public suspend fun follow(monitor: NetworkMonitor) {
        declare(monitor.capability)
        monitor.observations.collect { sample(it.state) }
    }
}

/** How an `OS-NET-SOURCE` line names a [CellularSignal]. */
private val CellularSignal.token: String
    get() =
        when (this) {
            CellularSignal.Signalled -> "Signalled"
            CellularSignal.Sampled -> "Sampled"
            CellularSignal.NotReported -> "NotReported"
        }
