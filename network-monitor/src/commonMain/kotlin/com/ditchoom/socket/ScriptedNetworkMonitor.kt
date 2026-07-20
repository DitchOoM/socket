package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * The monitor starts at the script's initial state and stays there until [play] is invoked; [play] then
 * advances [availability] and [networkId] at each scheduled offset by [delay]-ing the caller's
 * coroutine. Under `kotlinx-coroutines-test` that is virtual time, so a full flap timeline resolves
 * instantly and identically every run — the hermetic auto-migration trigger.
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
    private val availabilityState = MutableStateFlow(script.initialAvailability)
    override val availability: StateFlow<NetworkAvailability> = availabilityState.asStateFlow()

    private val networkIdState = MutableStateFlow(script.initialNetworkId)
    override val networkId: StateFlow<NetworkId> = networkIdState.asStateFlow()

    /**
     * Plays [script] to completion on the calling coroutine, suspending between transitions with
     * [delay] (virtual time under `runTest`). Returns once the last transition has fired; a script with
     * no transitions returns immediately. Cancelling the caller stops playback at whatever state was
     * last applied. Calling [play] again re-runs the timeline (StateFlow de-dupes the repeated values).
     */
    suspend fun play() {
        var elapsed = Duration.ZERO
        for (transition in script.transitions) {
            val wait = transition.at - elapsed
            if (wait > Duration.ZERO) delay(wait)
            elapsed = transition.at
            when (transition) {
                is NetworkMonitorScript.Transition.Availability -> availabilityState.value = transition.value
                is NetworkMonitorScript.Transition.Network -> networkIdState.value = transition.id
            }
        }
    }

    /** Convenience: [launch][CoroutineScope.launch]es [play] in [scope] and returns the [Job]. */
    fun playIn(scope: CoroutineScope): Job = scope.launch { play() }

    /** Nothing to release — the fake owns no platform resources. Idempotent. */
    override fun close() {}
}
