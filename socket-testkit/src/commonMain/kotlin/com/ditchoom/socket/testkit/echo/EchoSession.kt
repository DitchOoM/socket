package com.ditchoom.socket.testkit.echo

import kotlin.time.Duration

/** What the stream handed the probe for one exchange. */
public sealed interface StreamReply {
    public data class Echoed(
        val text: String,
    ) : StreamReply

    /** The peer sent FIN. */
    public data object PeerEnded : StreamReply

    /** The peer sent RESET_STREAM. */
    public data object PeerReset : StreamReply

    /** The read deadline passed; the reply is still owed and is judged when it arrives. */
    public data object StillOwed : StreamReply
}

/** How one connection's echo session ended. */
public sealed interface SessionEnd {
    public val label: String

    /** The session itself left: the stream was no longer being echoed. */
    public sealed interface StreamGone : SessionEnd {
        public data object PeerEndedStream : StreamGone {
            override val label: String = "PeerEndedStream"
        }

        public data object PeerResetStream : StreamGone {
            override val label: String = "PeerResetStream"
        }

        public data class WritesStalled(
            val consecutiveTimeouts: Int,
        ) : StreamGone {
            override val label: String get() = "WritesStalled($consecutiveTimeouts)"
        }
    }

    /** The connection closed under the stream. */
    public data object ConnectionDead : SessionEnd {
        override val label: String = "ConnectionDead"
    }

    /** The walk's deadline passed with the stream still open. */
    public data object WalkOver : SessionEnd {
        override val label: String = "WalkOver"
    }

    /** The connection scope threw before or between exchanges. */
    public data object ScopeFailed : SessionEnd {
        override val label: String = "ScopeFailed"
    }
}

/** What the echo loop does next, decided from one exchange. */
public sealed interface EchoStep {
    /** The loop went round: progress, whatever the reply was. */
    public sealed interface Exchanged : EchoStep {
        public data class Judged(
            val read: EchoRead,
        ) : Exchanged

        public data object StillOwed : Exchanged
    }

    /** The write did not complete within its deadline: the loop did not go round. */
    public data class WriteTimedOut(
        val seq: Int,
        val waited: Duration,
        val consecutive: Int,
    ) : EchoStep {
        public val line: String get() = "ECHO-WRITE-TIMEOUT seq=$seq waited=${waited.inWholeMilliseconds}ms consecutive=$consecutive"
    }

    /** Leave the connection scope; [end] is already recorded on the session. */
    public data class Reconnect(
        val end: SessionEnd.StreamGone,
        val seq: Int,
        val owedBytes: Int,
    ) : EchoStep {
        public val line: String
            get() =
                when (end) {
                    SessionEnd.StreamGone.PeerEndedStream ->
                        "STREAM-ENDED-BY-PEER seq=$seq owed=${owedBytes}B — the peer stopped echoing; leaving scope to reconnect"
                    SessionEnd.StreamGone.PeerResetStream ->
                        "STREAM-RESET-BY-PEER seq=$seq owed=${owedBytes}B — the peer aborted the stream; leaving scope to reconnect"
                    is SessionEnd.StreamGone.WritesStalled ->
                        "STREAM-WRITES-STALLED seq=$seq consecutiveTimeouts=${end.consecutiveTimeouts} owed=${owedBytes}B — " +
                            "the peer is not draining the stream; leaving scope to reconnect"
                }
    }
}

/** What one connection's session left behind, rendered in the grammar `analyze.py` reads. */
public data class EchoSessionReport(
    val end: SessionEnd,
    val unanswered: EchoUnanswered,
    val liveness: EchoLivenessVerdict,
) {
    public fun lines(tag: String): List<String> =
        buildList {
            when (unanswered) {
                EchoUnanswered.None -> Unit
                is EchoUnanswered.Some -> add(unanswered.line)
            }
            add("ECHO-LIVENESS $tag ${liveness.summary} ended=${end.label}")
        }
}

/**
 * One connection's echo exchanges: the [EchoLedger] that judges each reply, what the loop should do
 * after it, how long the stream went without an answered echo, and how the session ended.
 *
 * Owned by one echo loop; not shared. Every time is the caller's own clock, so a scripted sequence
 * drives it exactly as a walk does.
 */
public class EchoSession(
    connectedAt: Duration,
    private val quietLimit: Duration = EchoLivenessVerdict.QUIET_LIMIT,
    private val writeTimeoutStreakLimit: Int = WRITE_TIMEOUT_STREAK_LIMIT,
) {
    private sealed interface Lifetime {
        data object Open : Lifetime

        data class Ended(
            val end: SessionEnd,
            val at: Duration,
        ) : Lifetime
    }

    private val ledger = EchoLedger()
    private var lifetime: Lifetime = Lifetime.Open
    private var answered = 0
    private var lastAnsweredAt = connectedAt
    private var longestQuiet = QuietGap(from = connectedAt, length = Duration.ZERO)
    private var consecutiveWriteTimeouts = 0

    /** Exchanges the loop completed; the silence watchdog's measure of progress. */
    public var exchanges: Int = 0
        private set

    public val readDeadline: Duration get() = ledger.readDeadline
    public val owedBytes: Int get() = ledger.owedBytes

    public fun sent(
        seq: Int,
        payload: String,
        at: Duration,
    ) {
        consecutiveWriteTimeouts = 0
        ledger.sent(seq, payload, at)
    }

    public fun writeTimedOut(
        seq: Int,
        waited: Duration,
        at: Duration,
    ): EchoStep {
        consecutiveWriteTimeouts++
        return if (consecutiveWriteTimeouts >= writeTimeoutStreakLimit) {
            leave(SessionEnd.StreamGone.WritesStalled(consecutiveWriteTimeouts), seq, at)
        } else {
            EchoStep.WriteTimedOut(seq, waited, consecutiveWriteTimeouts)
        }
    }

    public fun reply(
        seq: Int,
        reply: StreamReply,
        at: Duration,
    ): EchoStep {
        exchanges++
        return when (reply) {
            is StreamReply.Echoed -> EchoStep.Exchanged.Judged(judge(reply.text, at))
            StreamReply.StillOwed -> EchoStep.Exchanged.StillOwed
            StreamReply.PeerEnded -> leave(SessionEnd.StreamGone.PeerEndedStream, seq, at)
            StreamReply.PeerReset -> leave(SessionEnd.StreamGone.PeerResetStream, seq, at)
        }
    }

    /** The exchanges that crossed their own deadline since the last call, oldest first. */
    public fun overdue(at: Duration): List<EchoOverdue> = ledger.overdue(at)

    /** The session learned how it ended; the first end stands. */
    public fun ended(
        end: SessionEnd,
        at: Duration,
    ) {
        settle(end, at)
    }

    /** Close the session: [fallback] stands only if it has not already ended. */
    public fun close(
        fallback: SessionEnd,
        at: Duration,
    ): EchoSessionReport {
        val ended = settle(fallback, at)
        noteQuiet(ended.at)
        return EchoSessionReport(
            end = ended.end,
            unanswered = ledger.abandon(),
            liveness = EchoLivenessVerdict.of(answered, longestQuiet, quietLimit),
        )
    }

    private fun settle(
        end: SessionEnd,
        at: Duration,
    ): Lifetime.Ended =
        when (val life = lifetime) {
            Lifetime.Open -> Lifetime.Ended(end, at).also { lifetime = it }
            is Lifetime.Ended -> life
        }

    private fun judge(
        text: String,
        at: Duration,
    ): EchoRead {
        val read = ledger.received(text, at)
        if (read is EchoRead.Consumed && read.outcomes.isNotEmpty()) {
            noteQuiet(at)
            lastAnsweredAt = at
            answered += read.outcomes.size
        }
        return read
    }

    private fun noteQuiet(at: Duration) {
        val gap = at - lastAnsweredAt
        if (gap > longestQuiet.length) longestQuiet = QuietGap(from = lastAnsweredAt, length = gap)
    }

    private fun leave(
        end: SessionEnd.StreamGone,
        seq: Int,
        at: Duration,
    ): EchoStep.Reconnect {
        ended(end, at)
        return EchoStep.Reconnect(end, seq, ledger.owedBytes)
    }

    public companion object {
        /** With a 5 s write deadline, a minute of writes the peer will not drain. */
        public const val WRITE_TIMEOUT_STREAK_LIMIT: Int = 12
    }
}
