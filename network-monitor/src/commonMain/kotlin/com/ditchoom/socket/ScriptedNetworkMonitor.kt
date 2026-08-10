package com.ditchoom.socket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * A [NetworkMonitor] that reports a fixed [NetworkMonitorScript] instead of observing a real platform —
 * the deterministic way to drive network-dependent behaviour (QUIC auto-migration, transport-fallback
 * backoff resets, ICE restarts in `../webrtc`) without a physical Wi-Fi↔cellular switch. It ships in
 * `:network-monitor` alongside [NetworkMonitor.AlwaysAvailable] so any consumer of the network-awareness
 * contract can use it without pulling in `:socket`'s TCP/TLS stack.
 *
 * The monitor starts at the script's [initialState][NetworkMonitorScript.initialState] and stays there
 * until [play] is invoked; [play] then advances [state] at each scheduled offset by [delay]-ing the
 * caller's coroutine. Under `kotlinx-coroutines-test` that is **virtual time**, so a full flap timeline
 * resolves instantly and identically every run — the hermetic auto-migration trigger.
 *
 * It reports the script's declared [NetworkMonitorScript.capability] verbatim, and the script validated
 * every one of its states against that capability at construction ([ReachResolution.permits]). So this
 * fake cannot emit a state the monitor it stands in for could not have produced — a consumer test is
 * always written against a legal timeline, which is the whole reason the capability lives on the script
 * rather than being passed in here.
 *
 * This reports its scripted mechanism even when that is [MonitorMechanism.PlatformSignalled], which is
 * what lets a consumer that gates a feature on reactivity (`../webrtc`'s
 * `IceRestartPolicy.OnNetworkChange`) be exercised at all: a [MonitorMechanism.Polled] or
 * [MonitorMechanism.Static] answer would make the feature-under-test disable itself and the scripted
 * timeline would prove nothing.
 *
 * Wire it exactly like a real monitor. Subscribe collectors first, then start playback, so no early
 * transition is missed:
 * ```
 * val monitor = ScriptedNetworkMonitor(script)
 * scope.launch { conn.state.collect { … } }   // subscribe
 * runCurrent()
 * val playback = monitor.playIn(scope)         // drive the timeline
 * ```
 */
class ScriptedNetworkMonitor(
    /** The timeline this monitor plays. Its initial state is what the monitor reports before [play]. */
    val script: NetworkMonitorScript,
) : NetworkMonitor {
    private val stateFlow = MutableStateFlow(script.initialState)
    override val state: StateFlow<NetworkState> = stateFlow.asStateFlow()

    /**
     * One bump per transition *applied*, exactly as a platform monitor bumps once per OS callback —
     * including a transition to the state already published, which the [state] flow de-dupes away. A
     * script that repeats a state therefore models real platform chatter (the callback burst a weak
     * radio produces before any rung changes), and a consumer's density logic can be exercised under
     * virtual time.
     */
    private val observationRelay = ObservationRelay(state)
    override val observationCount: StateFlow<Long> = observationRelay.count

    /**
     * Every applied transition, repeats included — so a script round-trips. Without this override the
     * inherited default ([NetworkMonitor.state]) would de-dupe the repeats back out, and re-recording a
     * replayed ride would silently lose exactly the chatter the script was written to carry.
     */
    override val observations: Flow<NetworkState> = observationRelay.observations

    /** The script's declared capability, reported verbatim — every scripted state was checked against it. */
    override val capability: MonitorCapability = script.capability

    /**
     * Plays [script] to completion on the calling coroutine, suspending between transitions with [delay]
     * (virtual time under `runTest`). Returns once the last transition has fired; a script with no
     * transitions returns immediately. Cancelling the caller stops playback at whatever state was last
     * applied. Calling [play] again re-runs the timeline (StateFlow de-dupes the repeated values;
     * [observationCount] keeps counting, as a real monitor's would).
     */
    suspend fun play() {
        var elapsed = Duration.ZERO
        for (transition in script.transitions) {
            val wait = transition.at - elapsed
            if (wait > Duration.ZERO) delay(wait)
            elapsed = transition.at
            stateFlow.value = transition.state
            observationRelay.record()
        }
    }

    /** Convenience: [launch][CoroutineScope.launch]es [play] in [scope] and returns the [Job]. */
    fun playIn(scope: CoroutineScope): Job = scope.launch { play() }

    /** Nothing to release — the fake owns no platform resources. Idempotent. */
    override fun close() {}
}
