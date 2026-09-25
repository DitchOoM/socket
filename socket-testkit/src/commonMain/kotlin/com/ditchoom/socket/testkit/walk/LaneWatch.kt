package com.ditchoom.socket.testkit.walk

import kotlin.concurrent.Volatile
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * One lane's stall watchdog. The lane's own coroutine publishes its [phase] as it moves through
 * [nextAttempt], [connected], [progressed], [backingOff] and [walkOver]; the heartbeat alone calls
 * [beat], which judges that phase against its own expectation in [bounds]. One per lane, because a
 * count summed across lanes keeps moving while one lane stands still.
 *
 * A stall is called only when the same episode is overdue on two consecutive beats, so the lane has had
 * a whole beat of the heartbeat's own running time to move: a process the OS stopped scheduling wakes
 * with every deadline behind it, and the heartbeat may run before the lane does.
 *
 * [clock] must be monotonic, or a wall-clock correction reads as a phase overrunning its bound.
 */
public class LaneWatch(
    public val lane: WalkLane,
    private val bounds: StallBounds,
    private val clock: () -> Duration,
    startsAfter: Duration,
) {
    /** Written by the lane's coroutine only, as one immutable value, so the heartbeat reads it whole. */
    @Volatile
    public var phase: LanePhase = clock().let { now -> LanePhase.Backoff(attempt = 0, since = now, until = now + startsAfter) }
        private set

    /** The lane's current connection attempt, 1-based; 0 before the first. */
    public val attempt: Int get() = phase.attempt

    /** Reads the lane's echo loops have completed across every connection. */
    @Volatile
    public var loopTicks: Int = 0
        private set

    private var ticksAtConnect = 0

    /** Where the lane's echo silence is measured from; see [LanePhase.Echoing.silentSince]. */
    private sealed interface Silence {
        /** The lane's last attempt did not connect, so the next connection starts the count afresh. */
        data object NoConnection : Silence

        data class Since(
            val at: Duration,
        ) : Silence
    }

    private var silence: Silence = Silence.NoConnection

    /** A new attempt starts connecting; returns its number. */
    public fun nextAttempt(): Int {
        val next = phase.attempt + 1
        phase = LanePhase.Connecting(next, clock())
        return next
    }

    /** The attempt connected and its echo loop is starting; its reads count on from the lane's total. */
    public fun connected() {
        ticksAtConnect = loopTicks
        val now = clock()
        val since =
            when (val s = silence) {
                Silence.NoConnection -> Silence.Since(now)
                is Silence.Since -> s
            }
        silence = since
        phase = LanePhase.Echoing(phase.attempt, since = now, loopTicks = loopTicks, silentSince = since.at)
    }

    /** The current connection's session has completed [exchanges] reads. */
    public fun progressed(exchanges: Int) {
        val ticks = ticksAtConnect + exchanges
        if (ticks == loopTicks) return
        loopTicks = ticks
        val now = clock()
        silence = Silence.Since(now)
        val current = phase
        if (current is LanePhase.Echoing) phase = current.copy(loopTicks = ticks, silentSince = now)
    }

    /** The lane waits [delay] before its next attempt. */
    public fun backingOff(delay: Duration) {
        val now = clock()
        val ended = phase
        if (ended is LanePhase.Connecting) silence = Silence.NoConnection
        phase = LanePhase.Backoff(ended.attempt, since = now, until = now + delay)
    }

    /** The lane will not attempt again. */
    public fun walkOver() {
        phase = LanePhase.WalkOver(phase.attempt, clock())
    }

    /**
     * One run of owing the same thing: the same kind of phase with its clock started at the same time.
     * A reconnect that leaves echo silence where it was is the same episode; a completed read is not.
     */
    private data class Episode(
        val kind: KClass<out LanePhase.Watched>,
        val owedSince: Duration,
    ) {
        constructor(phase: LanePhase.Watched) : this(phase::class, phase.owedSince)
    }

    /** What the heartbeat has concluded so far; touched by [beat] only. */
    private sealed interface Watch {
        data object Clear : Watch

        /** [episode] was overdue at the last beat. */
        data class Suspect(
            val episode: Episode,
        ) : Watch

        /** [episode] was called a stall while the lane was in [phase]. */
        data class Alarmed(
            val episode: Episode,
            val phase: LanePhase.Watched,
        ) : Watch
    }

    private var watch: Watch = Watch.Clear

    /** One heartbeat's judgement of the lane's current phase. */
    public fun beat(): Beat {
        val current = phase
        val previous = watch
        val beat =
            when (current) {
                is LanePhase.WalkOver -> {
                    watch = Watch.Clear
                    Beat.Unwatched
                }
                is LanePhase.Watched -> {
                    val episode = Episode(current)
                    val expectation = bounds.expectation(current, clock())
                    when {
                        previous is Watch.Alarmed && previous.episode == episode -> return Beat.StillStalled
                        !expectation.overdue -> {
                            watch = Watch.Clear
                            Beat.OnTime
                        }
                        previous is Watch.Suspect && previous.episode == episode -> {
                            watch = Watch.Alarmed(episode, current)
                            Beat.Stalled(current, expectation)
                        }
                        else -> {
                            watch = Watch.Suspect(episode)
                            Beat.Overdue(current, expectation)
                        }
                    }
                }
            }
        return if (previous is Watch.Alarmed) Beat.Recovered(stalled = previous.phase, now = current) else beat
    }

    /** What one heartbeat found. */
    public sealed interface Beat {
        /** The phase is within its bound. */
        public data object OnTime : Beat

        /** The walk is over; nothing is owed. */
        public data object Unwatched : Beat

        /** Past its bound for the first time; a stall if the next beat finds the same phase still here. */
        public data class Overdue(
            val phase: LanePhase.Watched,
            val expectation: StallBounds.Expectation,
        ) : Beat

        /** The crossing: the same phase overdue on two beats in a row. Reported once per stall. */
        public data class Stalled(
            val phase: LanePhase.Watched,
            val expectation: StallBounds.Expectation,
        ) : Beat {
            public fun line(ringSize: Int): String =
                "STALL-SUSPECTED ${phase.label} waited=${expectation.waited.inWholeSeconds}s " +
                    "bound=${expectation.bound.inWholeSeconds}s — ${phase.owed}. Dumping the last $ringSize trace events."
        }

        /** The stalled phase has not moved since it was reported. */
        public data object StillStalled : Beat

        /** The lane moved on from the phase that was called a stall. */
        public data class Recovered(
            val stalled: LanePhase,
            val now: LanePhase,
        ) : Beat {
            public val line: String get() = "STALL-RECOVERED ${stalled.label} now ${now.label}"
        }
    }
}
