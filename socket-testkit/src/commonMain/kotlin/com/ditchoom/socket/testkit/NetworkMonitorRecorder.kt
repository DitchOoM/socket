package com.ditchoom.socket.testkit

import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkMonitorScript
import com.ditchoom.socket.ScriptedNetworkMonitor
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

/**
 * Records a live [NetworkMonitor]'s emissions as replayable [TraceEvent.NetAvail]/[TraceEvent.Net]
 * input events on a neutral [TraceSink] — the capture half of the network-observation record/replay
 * loop (RFC_UNIFIED_NETWORK_TEST_HARNESS §7). The recorded trace round-trips back into a
 * [NetworkMonitorScript] via [networkMonitorScriptFromTrace], so a flap seen once on a real device
 * replays deterministically through a [ScriptedNetworkMonitor] forever after.
 *
 * It lives in `:socket-testkit` rather than next to [ScriptedNetworkMonitor] in `:network-monitor`
 * because it needs [TraceSink], which sits one module downstream — a recorder in `:network-monitor`
 * would force that lean module to depend on the testkit (a dependency cycle). The scripted monitor and
 * the script value type stay upstream so production consumers get the fake without the recorder.
 *
 * All stamps are nanoseconds from the recorder's origin, taken from [clock] — inject
 * `{ testScheduler.currentTime.milliseconds }` under `runTest` so a recording follows the same virtual
 * clock the rest of the harness runs on; the default is a monotonic wall clock for live capture.
 * [observe] may run its two collectors concurrently, and [TraceSink.emit] tolerates that.
 */
class NetworkMonitorRecorder(
    private val sink: TraceSink,
    private val clock: () -> Duration = monotonicClock(),
) {
    private val origin: Duration = clock()

    private fun nowNanos(): Long = (clock() - origin).inWholeNanoseconds

    /** Record a `networkId` observation at the current instant. */
    fun networkId(id: NetworkId) {
        sink.emit(TraceEvent.Net(nowNanos(), id))
    }

    /** Record an `availability` observation at the current instant. */
    fun availability(value: NetworkAvailability) {
        sink.emit(TraceEvent.NetAvail(nowNanos(), value))
    }

    /**
     * Collects [monitor]'s `availability` and `networkId` StateFlows in [scope], recording every
     * emission — including each flow's initial replayed value, which becomes the script's initial
     * state on replay. Returns the parent [Job]; cancel it (or [scope]) to stop recording.
     */
    fun observe(
        monitor: NetworkMonitor,
        scope: CoroutineScope,
    ): Job =
        scope.launch {
            launch { monitor.availability.collect { availability(it) } }
            launch { monitor.networkId.collect { networkId(it) } }
        }

    companion object {
        private fun monotonicClock(): () -> Duration {
            val mark = TimeSource.Monotonic.markNow()
            return { mark.elapsedNow() }
        }
    }
}

/**
 * Rebuilds the [NetworkMonitorScript] that a [NetworkMonitorRecorder] captured from a real monitor —
 * the inverse of recording, closing the record→fixture→replay loop for network observations.
 *
 * Only the network input events ([TraceEvent.NetAvail]/[TraceEvent.Net]) are consulted; any other
 * trace events are ignored, so a mixed QUIC/network trace can be projected straight to a monitor
 * script. Each flow's **first** recorded value is the script's initial state (a monitor reports its
 * current value the moment a recorder subscribes); every later value becomes a transition at its
 * recorded offset. With no recorded availability/networkId the script defaults to
 * `AVAILABLE`/[NetworkId.Unidentified], matching a monitor that never reported.
 */
fun networkMonitorScriptFromTrace(events: List<TraceEvent>): NetworkMonitorScript {
    val avails = events.filterIsInstance<TraceEvent.NetAvail>()
    val nets = events.filterIsInstance<TraceEvent.Net>()
    val transitions =
        buildList {
            avails.drop(1).forEach { add(NetworkMonitorScript.Transition.Availability(it.atNanos.nanoseconds, it.value)) }
            nets.drop(1).forEach { add(NetworkMonitorScript.Transition.Network(it.atNanos.nanoseconds, it.id)) }
        }.sortedBy { it.at }
    return NetworkMonitorScript(
        initialAvailability = avails.firstOrNull()?.value ?: NetworkAvailability.AVAILABLE,
        initialNetworkId = nets.firstOrNull()?.id ?: NetworkId.Unidentified,
        transitions = transitions,
    )
}
