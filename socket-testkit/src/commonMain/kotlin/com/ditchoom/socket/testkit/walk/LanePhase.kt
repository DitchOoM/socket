package com.ditchoom.socket.testkit.walk

import kotlin.time.Duration

/**
 * Where one lane of the walk is, and since when. Each phase carries its own expectation of progress,
 * which is what the lane's stall watchdog judges: a lane with no connection has no echo loop to be
 * stuck, but it does have a connect that must resolve or a backoff that must end.
 *
 * [attempt] is the number of connection attempts the lane has started, 0 before the first.
 */
public sealed interface LanePhase {
    /** A phase that owes progress within a bound. */
    public sealed interface Watched : LanePhase {
        /** What the phase owes and has not delivered: the expected-versus-observed half of a stall line. */
        public val owed: String

        /** When the clock on what this phase owes started. Nothing the lane did since then paid it. */
        public val owedSince: Duration
    }

    public val attempt: Int
    public val since: Duration

    /** The token after `phase=` in a log line. */
    public val name: String

    /** `phase=… attempt=…`, plus whatever else this phase is judged on. */
    public val label: String get() = "phase=$name attempt=$attempt"

    /** Waiting out a known delay before the next attempt: the start stagger or a reconnect backoff. */
    public data class Backoff(
        override val attempt: Int,
        override val since: Duration,
        val until: Duration,
    ) : Watched {
        override val name: String get() = "Backoff"
        override val owedSince: Duration get() = since
        override val owed: String
            get() = "expected attempt ${attempt + 1} when the ${(until - since).inWholeSeconds}s backoff ended; none started"
    }

    /** Attempt [attempt] has started and has neither connected nor failed. */
    public data class Connecting(
        override val attempt: Int,
        override val since: Duration,
    ) : Watched {
        override val name: String get() = "Connecting"
        override val owedSince: Duration get() = since
        override val owed: String get() = "expected the connect to succeed or fail; it has done neither"
    }

    /**
     * Attempt [attempt] is connected and echoing. [loopTicks] counts the reads the lane's echo loops
     * have completed across every connection.
     *
     * [silentSince] is when the lane last completed a read, or, if it has not since its connects last
     * failed, when it connected again. Silence carries across connections that connect and end
     * without a read, because a lane that keeps reconnecting and never echoes is not echoing; it
     * restarts after a failed connect, because the time with no network is the connect's to answer
     * for, not the echo loop's.
     */
    public data class Echoing(
        override val attempt: Int,
        override val since: Duration,
        val loopTicks: Int,
        val silentSince: Duration,
    ) : Watched {
        override val name: String get() = "Echoing"
        override val owedSince: Duration get() = silentSince
        override val label: String get() = "phase=$name attempt=$attempt loopTicks=$loopTicks"
        override val owed: String get() = "expected loopTicks to advance; the echo loop is not running"
    }

    /** The walk's deadline has passed and the lane will not attempt again. Nothing is expected of it. */
    public data class WalkOver(
        override val attempt: Int,
        override val since: Duration,
    ) : LanePhase {
        override val name: String get() = "WalkOver"
    }
}

/**
 * How long each phase may go without the progress it owes before the watchdog suspects a stall.
 *
 * @property echoSilence how long an echo loop may go without completing a read.
 * @property connect how long an attempt may take to connect or fail: the connection's own handshake
 *   bound (for QUIC, its idle timeout) plus a margin. See [forConnect].
 * @property backoffOverrun how far past its deadline a backoff may run before the next attempt starts.
 */
public data class StallBounds(
    val echoSilence: Duration,
    val connect: Duration,
    val backoffOverrun: Duration,
) {
    /** The progress [phase] owes by [now]: how long it has waited, against how long it may. */
    public fun expectation(
        phase: LanePhase.Watched,
        now: Duration,
    ): Expectation =
        Expectation(
            waited = now - phase.owedSince,
            bound =
                when (phase) {
                    is LanePhase.Backoff -> phase.until - phase.since + backoffOverrun
                    is LanePhase.Connecting -> connect
                    is LanePhase.Echoing -> echoSilence
                },
        )

    public data class Expectation(
        val waited: Duration,
        val bound: Duration,
    ) {
        val overdue: Boolean get() = waited > bound
    }

    public companion object {
        /** A connect bound of the connection's own [handshakeBound] plus [margin]. */
        public fun forConnect(
            echoSilence: Duration,
            handshakeBound: Duration,
            margin: Duration,
        ): StallBounds = StallBounds(echoSilence = echoSilence, connect = handshakeBound + margin, backoffOverrun = margin)
    }
}
