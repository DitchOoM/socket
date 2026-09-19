package com.ditchoom.socket.testkit.echo

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The longest stretch a stream went without an answered echo, and when it began. */
public data class QuietGap(
    val from: Duration,
    val length: Duration,
)

/**
 * Whether a connection's stream was actually being echoed.
 *
 * The path layer can keep migrating and validating for days while the stream carries nothing —
 * every PATH_CHALLENGE answered, every echo unanswered — so a migration verdict alone over-reads a
 * connection that echoed nothing. This is the verdict that says so.
 */
public sealed interface EchoLivenessVerdict {
    public val answered: Int
    public val longestQuiet: QuietGap
    public val limit: Duration

    /** The operator-facing summary, without a scope tag. */
    public val summary: String

    /** Every gap between answered echoes stayed inside [limit]. */
    public data class Live(
        override val answered: Int,
        override val longestQuiet: QuietGap,
        override val limit: Duration,
    ) : EchoLivenessVerdict {
        override val summary: String
            get() =
                "LIVE — answered=$answered longestQuietMs=${longestQuiet.length.inWholeMilliseconds} " +
                    "limitMs=${limit.inWholeMilliseconds}"
    }

    /** The stream went longer than [limit] without an answered echo: it was not being echoed. */
    public data class Silent(
        override val answered: Int,
        override val longestQuiet: QuietGap,
        override val limit: Duration,
    ) : EchoLivenessVerdict {
        override val summary: String
            get() =
                "SILENT — answered=$answered longestQuietMs=${longestQuiet.length.inWholeMilliseconds} " +
                    "quietFromMs=${longestQuiet.from.inWholeMilliseconds} limitMs=${limit.inWholeMilliseconds}: " +
                    "no answered echo for ${longestQuiet.length} (from t+${longestQuiet.from}), past the $limit limit"
    }

    public companion object {
        /**
         * Longer than any silence a healthy walk produces: a reconnect backoff is 60 s and a dead radio
         * ends the connection at its 30 s idle timeout, so a connection that stays up and answers
         * nothing for ten minutes is not being echoed.
         */
        public val QUIET_LIMIT: Duration = 10.minutes

        public fun of(
            answered: Int,
            longestQuiet: QuietGap,
            limit: Duration,
        ): EchoLivenessVerdict =
            if (longestQuiet.length > limit) {
                Silent(answered, longestQuiet, limit)
            } else {
                Live(answered, longestQuiet, limit)
            }
    }
}

/** The run's echo liveness: the worst connection decides, the rest is the roll-up. */
public sealed interface RunLiveness {
    public val line: String

    public data object NeverConnected : RunLiveness {
        override val line: String = "ECHO-LIVENESS run NEVER-CONNECTED — no connection was established"
    }

    public data class Live(
        val connections: Int,
        val answered: Int,
        val worstConnection: Int,
        val longestQuiet: QuietGap,
        val limit: Duration,
        val endings: Map<String, Int>,
    ) : RunLiveness {
        override val line: String
            get() =
                "ECHO-LIVENESS run LIVE — connections=$connections answered=$answered " +
                    "longestQuietMs=${longestQuiet.length.inWholeMilliseconds} onConnection=$worstConnection " +
                    "limitMs=${limit.inWholeMilliseconds} ended=[${endings.render()}]"
    }

    public data class Silent(
        val connections: Int,
        val answered: Int,
        val worstConnection: Int,
        val longestQuiet: QuietGap,
        val limit: Duration,
        val endings: Map<String, Int>,
    ) : RunLiveness {
        override val line: String
            get() =
                "ECHO-LIVENESS run SILENT — connection $worstConnection went ${longestQuiet.length} without an answered " +
                    "echo (from t+${longestQuiet.from}; limit $limit); connections=$connections answered=$answered " +
                    "longestQuietMs=${longestQuiet.length.inWholeMilliseconds} ended=[${endings.render()}]"
    }
}

private fun Map<String, Int>.render(): String = entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "none" }

/** Run-wide roll-up of every connection's [EchoSessionReport]. */
public class EchoLivenessTotals(
    private val limit: Duration = EchoLivenessVerdict.QUIET_LIMIT,
) {
    private sealed interface Worst {
        data object None : Worst

        data class Of(
            val connection: Int,
            val gap: QuietGap,
        ) : Worst
    }

    private var connections = 0
    private var answered = 0
    private var worst: Worst = Worst.None
    private val endings = LinkedHashMap<String, Int>()

    public fun absorb(
        connection: Int,
        report: EchoSessionReport,
    ) {
        connections++
        answered += report.liveness.answered
        endings[report.end.label] = (endings[report.end.label] ?: 0) + 1
        val gap = report.liveness.longestQuiet
        worst =
            when (val current = worst) {
                Worst.None -> Worst.Of(connection, gap)
                is Worst.Of -> if (gap.length > current.gap.length) Worst.Of(connection, gap) else current
            }
    }

    public fun verdict(): RunLiveness =
        when (val w = worst) {
            Worst.None -> RunLiveness.NeverConnected
            is Worst.Of ->
                if (w.gap.length > limit) {
                    RunLiveness.Silent(connections, answered, w.connection, w.gap, limit, endings.toMap())
                } else {
                    RunLiveness.Live(connections, answered, w.connection, w.gap, limit, endings.toMap())
                }
        }
}
