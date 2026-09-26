package com.ditchoom.socket.testkit.walk

import com.ditchoom.socket.testkit.echo.EchoSession
import com.ditchoom.socket.testkit.echo.EchoSessionReport
import com.ditchoom.socket.testkit.echo.SessionEnd
import kotlin.time.Duration

/** Why a connect attempt never completed its handshake. */
public sealed interface ConnectFailure {
    public val label: String

    /** The host had no route to the peer's address family: the route probe could not be opened. */
    public data object UnresolvedRoute : ConnectFailure {
        override val label: String = "UnresolvedRoute"
    }

    /** Any other failure, labelled by the type the connect threw. */
    public data class Threw(
        val type: String,
    ) : ConnectFailure {
        override val label: String get() = type
    }
}

/**
 * What one connect attempt came to. Only an [Established] attempt is a connection: it alone has a
 * session, a liveness verdict and a `447-VERDICT`. A [NeverEstablished] one is counted as an attempt
 * and nothing else — it carried no stream and no path, so there is nothing about either to judge.
 */
public sealed interface AttemptOutcome {
    public data class Established(
        val report: EchoSessionReport,
    ) : AttemptOutcome

    public data class NeverEstablished(
        val failure: ConnectFailure,
    ) : AttemptOutcome {
        /** The attempt's one line, keyed like its `CONNECT-ATTEMPT` so the two pair up. */
        public fun line(
            attempt: Int,
            target: WalkTarget,
        ): String = "CONNECT-NEVER-ESTABLISHED n=$attempt family=${target.family.label} reason=${failure.label}"
    }
}

/**
 * One connect attempt: nothing but an attempt until its handshake completes, a connection with an
 * [EchoSession] from then on. Owned by one lane's connect loop; not shared.
 */
public class ConnectAttempt {
    private sealed interface Phase {
        data object Connecting : Phase

        data class Connected(
            val session: EchoSession,
        ) : Phase
    }

    private var phase: Phase = Phase.Connecting

    /** The handshake completed at [at]: the attempt is a connection, and this is its session. */
    public fun established(at: Duration): EchoSession = EchoSession(connectedAt = at).also { phase = Phase.Connected(it) }

    /**
     * The connect scope threw. A connection closes its session with [sessionEnd] standing unless it had
     * already ended; an attempt still connecting never established, for [failure].
     */
    public fun failed(
        failure: ConnectFailure,
        sessionEnd: SessionEnd,
        at: Duration,
    ): AttemptOutcome =
        when (val p = phase) {
            Phase.Connecting -> AttemptOutcome.NeverEstablished(failure)
            is Phase.Connected -> AttemptOutcome.Established(p.session.close(fallback = sessionEnd, at = at))
        }
}

/** One lane's roll-up of its attempts, or the run's sum of them: attempts and connections apart. */
public class AttemptTotals {
    private var attempts = 0
    private var established = 0
    private val neverEstablished = LinkedHashMap<String, Int>()

    public fun absorb(outcome: AttemptOutcome) {
        attempts++
        when (outcome) {
            is AttemptOutcome.Established -> established++
            is AttemptOutcome.NeverEstablished ->
                neverEstablished[outcome.failure.label] = (neverEstablished[outcome.failure.label] ?: 0) + 1
        }
    }

    /** Another lane's totals, summed in. */
    public fun add(other: AttemptTotals) {
        attempts += other.attempts
        established += other.established
        other.neverEstablished.forEach { (k, v) -> neverEstablished[k] = (neverEstablished[k] ?: 0) + v }
    }

    public val line: String
        get() {
            val failures = neverEstablished.entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "none" }
            return "CONNECT-TOTALS attempts=$attempts established=$established neverEstablished=[$failures]"
        }
}
