package com.ditchoom.socket.testkit.echo

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Why a lane echoes at the fast rate right now; the order is the order a `PACE` line names them in. */
public enum class PaceReason(
    public val token: String,
) {
    /** An exchange went late, unanswered or failed: watch closely until it has been clean a while. */
    Trouble("trouble"),

    /** The device's active link changed, or this lane's path did. */
    Network("network"),

    /** The device moved recently: handoffs happen on the move. */
    Moving("moving"),

    /** The connection is new and has not yet shown it is stable. */
    Settling("settling"),
}

/** The rate a lane echoes at, as the loop asks for it each round. */
public sealed interface EchoPace {
    /** How the run's `LOOP-SCHEDULE` line states this pace. */
    public val schedule: String

    /** The interval to the round after the one starting [at]. */
    public fun next(at: Duration): PaceStep

    /** The lane's connection was established at [at]. */
    public fun connected(at: Duration)

    /** An exchange on this lane went late, unanswered or failed at [at]. */
    public fun troubled(at: Duration)

    /** This lane's own path changed at [at] (a probe, a migration, a failed path). */
    public fun pathChanged(at: Duration)

    /** The rate never changes: the probes' behaviour before adaptive pacing, and every scripted test's. */
    public class Fixed(
        public val interval: Duration,
    ) : EchoPace {
        override val schedule: String get() = "intervalMs=${interval.inWholeMilliseconds}"

        override fun next(at: Duration): PaceStep = PaceStep.Same(interval)

        override fun connected(at: Duration): Unit = Unit

        override fun troubled(at: Duration): Unit = Unit

        override fun pathChanged(at: Duration): Unit = Unit
    }

    /**
     * Fast while anything is happening, slow while nothing is: [fast] while the connection is
     * [settle]-new, for [hold] after any trouble, path change or device network change, and while
     * the device moved within [motionHold]; [idle] otherwise. A walk spends most of its hours still
     * and steady, and at [fast] the radio never sleeps — the Samsung drained ~4 %/h at a fixed 250 ms
     * — while the moments that matter are exactly the ones these signals mark.
     *
     * One per lane, all reading the one [device]. Only the lane's own loop calls [next], so the
     * pace's state needs no lock; the device signals are written from platform callbacks.
     */
    public class Adaptive(
        private val device: DeviceSignals,
        public val fast: Duration = 250.milliseconds,
        public val idle: Duration = 5.seconds,
        private val settle: Duration = 10.seconds,
        private val hold: Duration = 30.seconds,
        private val motionHold: Duration = 2.minutes,
    ) : EchoPace {
        @Volatile private var connectedAt: Duration = Duration.ZERO

        @Volatile private var troubleAt: Duration = NEVER

        @Volatile private var pathAt: Duration = NEVER
        private var current: Pace = Pace.Unset

        override val schedule: String
            get() = "pace=adaptive intervalMs=${fast.inWholeMilliseconds} idleMs=${idle.inWholeMilliseconds}"

        private sealed interface Pace {
            data object Unset : Pace

            data class Fast(
                val reason: PaceReason,
            ) : Pace

            data object Idle : Pace
        }

        override fun connected(at: Duration) {
            connectedAt = at
        }

        override fun troubled(at: Duration) {
            troubleAt = at
        }

        override fun pathChanged(at: Duration) {
            pathAt = at
        }

        override fun next(at: Duration): PaceStep {
            val pace = paceAt(at)
            val interval = if (pace is Pace.Fast) fast else idle
            if (pace == current) return PaceStep.Same(interval)
            current = pace
            val line =
                when (pace) {
                    is Pace.Fast -> "PACE fast reason=${pace.reason.token} intervalMs=${fast.inWholeMilliseconds}"
                    else -> "PACE idle intervalMs=${idle.inWholeMilliseconds}"
                }
            return PaceStep.Changed(interval, line)
        }

        private fun paceAt(at: Duration): Pace =
            when {
                at - troubleAt < hold -> Pace.Fast(PaceReason.Trouble)
                at - maxOf(pathAt, device.networkChangedAt) < hold -> Pace.Fast(PaceReason.Network)
                at - device.movedAt < motionHold -> Pace.Fast(PaceReason.Moving)
                at - connectedAt < settle -> Pace.Fast(PaceReason.Settling)
                else -> Pace.Idle
            }
    }

    public companion object {
        /** Before every clock reading: `at - NEVER` is infinite, so no hold is ever in force from it. */
        internal val NEVER: Duration = -Duration.INFINITE
    }
}

/** One round's interval, and the `PACE` line to log when the pace just changed. */
public sealed interface PaceStep {
    public val interval: Duration

    public data class Same(
        override val interval: Duration,
    ) : PaceStep

    public data class Changed(
        override val interval: Duration,
        val line: String,
    ) : PaceStep
}

/**
 * What the device as a whole says about pace, shared by every lane: when its active link last
 * changed, and when it last moved. Written from platform callbacks on any thread, on the walk clock.
 */
public class DeviceSignals {
    @Volatile public var networkChangedAt: Duration = EchoPace.NEVER
        private set

    @Volatile public var movedAt: Duration = EchoPace.NEVER
        private set

    public fun networkChanged(at: Duration) {
        networkChangedAt = at
    }

    public fun moved(at: Duration) {
        movedAt = at
    }
}
