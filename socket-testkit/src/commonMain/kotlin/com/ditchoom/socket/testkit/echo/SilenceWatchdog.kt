package com.ditchoom.socket.testkit.echo

import kotlin.time.Duration

/**
 * Is the echo loop still going round? Compares the loop's progress count beat to beat, and calls a
 * stall once it has stood still for [quietBeatsBeforeAlarm] beats. Not fatal: a false alarm costs one
 * log line, a missed real one costs another multi-day walk.
 */
public class SilenceWatchdog(
    private val quietBeatsBeforeAlarm: Int,
    private val beatInterval: Duration,
) {
    /** What one heartbeat found. */
    public sealed interface Beat {
        public data object Progressing : Beat

        /** Unchanged, but not for long enough to call. */
        public data class Quiet(
            val beats: Int,
        ) : Beat

        /** The crossing: unchanged for the whole budget. Reported once per stall. */
        public data class Stalled(
            val progress: Int,
            val quietFor: Duration,
        ) : Beat {
            public fun line(
                attempt: Int,
                ringSize: Int,
            ): String =
                "STALL-SUSPECTED loopTicks=$progress unchanged for ${quietFor.inWholeSeconds}s attempt=$attempt — " +
                    "the echo loop is not running. Dumping the last $ringSize trace events."
        }

        public data class Recovered(
            val progress: Int,
            val quietBeats: Int,
        ) : Beat {
            public val line: String get() = "STALL-RECOVERED loopTicks=$progress after $quietBeats quiet heartbeat(s)"
        }
    }

    private sealed interface State {
        data object Fresh : State

        data class Watching(
            val progress: Int,
            val quietBeats: Int,
        ) : State

        data class Alarmed(
            val progress: Int,
            val quietBeats: Int,
        ) : State
    }

    private var state: State = State.Fresh

    public fun beat(progress: Int): Beat =
        when (val s = state) {
            State.Fresh -> {
                state = State.Watching(progress, 0)
                Beat.Progressing
            }
            is State.Watching ->
                if (progress != s.progress) {
                    state = State.Watching(progress, 0)
                    Beat.Progressing
                } else {
                    val quiet = s.quietBeats + 1
                    if (quiet >= quietBeatsBeforeAlarm) {
                        state = State.Alarmed(progress, quiet)
                        Beat.Stalled(progress, beatInterval * quiet)
                    } else {
                        state = State.Watching(progress, quiet)
                        Beat.Quiet(quiet)
                    }
                }
            is State.Alarmed ->
                if (progress != s.progress) {
                    state = State.Watching(progress, 0)
                    Beat.Recovered(progress, s.quietBeats)
                } else {
                    val quiet = s.quietBeats + 1
                    state = State.Alarmed(progress, quiet)
                    Beat.Quiet(quiet)
                }
        }
}
