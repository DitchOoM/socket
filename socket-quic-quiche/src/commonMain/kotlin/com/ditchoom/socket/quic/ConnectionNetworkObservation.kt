package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeMark

/**
 * One connection's view of its [NetworkMonitor], reduced to the single value
 * [QuicConnection.networkAtClose] reports.
 *
 * ## Why the monitor is shared, not re-resolved
 * The connection's engine resolves [QuicOptions.networkMonitor] **once** and hands that one instance to
 * automatic migration, to the trace tap, and to this. Sharing the instance is the invariant Phase 3b
 * wrote down and deferred: a second monitor would report an `ObservationSequence` indexing a different
 * stream than the one that triggered the migration — two unrelated counters that look joinable.
 *
 * ## Why this is sealed rather than a nullable field
 * A server-accepted connection observes no network, and `QuicheDriverTuning.networkObservation` used to
 * say so with `null`, read back as `networkObservation?.atClose ?: NetworkAtClose.NotObserved`. But
 * [NetworkAtClose] already *has* a truthful case for that — [NetworkAtClose.NotObserved] — so the
 * nullable was a second, redundant encoding of a state the type models properly, and every reader had to
 * remember to translate it. [Unobserved] is that state as an ordinary value: it collects nothing,
 * freezes to nothing, and reports `NotObserved`. Both the `?` and the `?:` disappear.
 */
@InternalQuicApi
sealed interface ConnectionNetworkObservation {
    /**
     * Live while the connection runs — a preview of what a close would record — and the value observed
     * at [freeze] once the connection has closed.
     */
    val atClose: NetworkAtClose

    /**
     * Start collecting the monitor into this observation, as a child of [scope] (the connection), so it
     * stops when the connection does.
     */
    fun collectInto(scope: CoroutineScope)

    /**
     * Latch [atClose] at the value observed **now**.
     *
     * Called from `QuicheDriver.transitionToClosed()`, deliberately not from a collector of the
     * connection's `state`: the connection's scope children are cancelled at close, so a state collector
     * may never run at all, and a freeze-on-first-read would report the network at *read* time — the
     * exact lie this type exists to remove. Idempotent, because `transitionToClosed` is.
     */
    fun freeze()

    /**
     * Nothing is watching this connection's network path.
     *
     * The honest answer for a **server-accepted** connection — it has no local client path to observe,
     * the same reason `wireClientConnectivityTap` is never called from `bind` — and for a client whose
     * resolved monitor is [NetworkMonitor.AlwaysAvailable], which never transitions and which
     * [NetworkAtClose.NotObserved]'s own KDoc names as exactly this case.
     */
    data object Unobserved : ConnectionNetworkObservation {
        override val atClose: NetworkAtClose get() = NetworkAtClose.NotObserved

        override fun collectInto(scope: CoroutineScope) = Unit

        override fun freeze() = Unit
    }

    /**
     * A connection watching a real [NetworkMonitor].
     *
     * ## Why it collects `observations` and not `state`
     * [NetworkAtClose.Observed] carries the very [NetworkObservation] value class the monitor emits, so
     * correlating a close against a monitor's stream is ordinary comparison. It also inherits that type's
     * honesty about density: `state` de-dupes, and de-duping is exactly what hides a link flapping hard
     * while every evaluation folds back to the same rung.
     *
     * ## Why `sinceLastChange` only resets on a *changed* state
     * It is the discriminator between a timer and a flap: a connection that died 133 seconds into a link
     * that never changed was killed by something periodic; one that died 1.2 seconds after a transition
     * was killed by the transition. Observations that fold to the same state therefore do **not** reset
     * it — they are the density signal, not a change.
     *
     * Measured on [DriverClock], not on wall-clock time, so a simulated or replayed connection reports
     * the same duration the run actually took (RFC_DETERMINISTIC_SIMULATION.md §5, "one clock").
     */
    class Monitored(
        private val monitor: NetworkMonitor,
        private val clock: DriverClock,
    ) : ConnectionNetworkObservation {
        private class Sample(
            val observation: NetworkObservation,
            val sinceLastChange: TimeMark,
        )

        /**
         * `MutableStateFlow` purely for its atomic publication: this is written by the observation
         * collector and read by the driver loop, which are different threads under the production
         * `Dispatchers.Default` driver context.
         *
         * Seeded from [NetworkMonitor.state] so a connection that dies before the collector's first
         * emission still reports the state it connected on. The seed is [NetworkObservation.Unsequenced]
         * because a state read genuinely carries no position in the observation stream — which is
         * precisely what that case means, not a degradation of it.
         */
        private val latest =
            MutableStateFlow(
                Sample(NetworkObservation.Unsequenced(monitor.state.value), clock.markNow()),
            )

        /** Set exactly once, by [freeze]; non-null thereafter and never re-read from [latest]. */
        private val frozen = MutableStateFlow<NetworkAtClose?>(null)

        override val atClose: NetworkAtClose get() = frozen.value ?: live()

        private fun live(): NetworkAtClose {
            val sample = latest.value
            return NetworkAtClose.Observed(sample.observation, sample.sinceLastChange.elapsedNow())
        }

        override fun collectInto(scope: CoroutineScope) {
            scope.launch {
                monitor.observations.collect { observation ->
                    val previous = latest.value
                    latest.value =
                        Sample(
                            observation,
                            // Only a *changed* state restarts the clock; a fold back to the same rung
                            // keeps the mark, so `sinceLastChange` answers "how long this state had been
                            // in effect" rather than "how recently the platform spoke".
                            if (previous.observation.state == observation.state) {
                                previous.sinceLastChange
                            } else {
                                clock.markNow()
                            },
                        )
                }
            }
        }

        override fun freeze() {
            frozen.compareAndSet(null, live())
        }
    }

    companion object {
        /**
         * The observation for a client connection on [monitor].
         *
         * [NetworkMonitor.AlwaysAvailable] resolves to [Unobserved] rather than to a [Monitored] that
         * would launch a collector over a constant — detected by identity, because that is what the
         * singleton is. One state, one representation: nothing downstream needs an `observesNothing`
         * branch.
         */
        fun of(
            monitor: NetworkMonitor,
            clock: DriverClock,
        ): ConnectionNetworkObservation = if (monitor === NetworkMonitor.AlwaysAvailable) Unobserved else Monitored(monitor, clock)
    }
}
