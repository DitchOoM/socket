package com.ditchoom.socket.testkit.migration

/**
 * What one connection's probe history says about its connection-ID pool (#447).
 *
 * The question is narrower than it looks, and the obvious reading of it is wrong: *after a probe went
 * unanswered, did this connection ever arm one that was **answered**?*
 *
 * - **Sending another probe is not recovery.** A retry ladder that fails three times sends three
 *   probes; counting them as evidence that the pool came back makes a run of failures read as a
 *   success.
 * - **A migration that succeeded earlier is not recovery either.** The pool's state before the first
 *   unanswered probe says nothing about its state after one, so every count here is scoped to what
 *   happened *after* a probe was lost.
 * - **A fresh connection proves nothing at all**, one level up: a reconnect negotiates a brand-new
 *   pool, so a later connection's clean migrations are not evidence about the one that lost a probe.
 *
 * Every leaf therefore names what was actually observed, and a connection that lost its path and
 * never got it back is a [NeverRecovered] — not an absence of evidence.
 */
sealed interface PoolRecoveryVerdict {
    /** The operator-facing line, keeping the vocabulary a walk log is already grepped for. */
    val line: String

    /** No probe went unanswered, so this connection exercises #445 only and cannot speak to #447. */
    data class NeverLostAProbe(
        val attempts: Int,
    ) : PoolRecoveryVerdict {
        override val line: String
            get() =
                "INCONCLUSIVE — no probe went unanswered on this connection, so it exercises #445 " +
                    "only and says nothing about #447 (attempts=$attempts)"
    }

    /** A probe armed after an unanswered one was answered: the pool handed out a usable CID again. */
    data class Recovered(
        val unanswered: Int,
        val answeredAfter: Int,
        val succeededAfter: Int,
    ) : PoolRecoveryVerdict {
        override val line: String
            get() =
                "PASS — $unanswered unanswered probe(s), then $answeredAfter later probe(s) were " +
                    "ANSWERED ($succeededAfter completing a migration): the pool recovered in the field"
    }

    /** A later attempt was refused for want of a CID — the pool did not come back. */
    data class Exhausted(
        val unanswered: Int,
        val noSpareAfter: Int,
    ) : PoolRecoveryVerdict {
        override val line: String
            get() =
                "REGRESSION — $unanswered unanswered probe(s), then $noSpareAfter later attempt(s) " +
                    "answered NoSpareConnectionId: the pool did not come back (#447 alive)"
    }

    /** Probes kept being armed after the first went unanswered, and not one of them was answered. */
    data class NeverRecovered(
        val unanswered: Int,
        val probedAfter: Int,
    ) : PoolRecoveryVerdict {
        override val line: String
            get() =
                "FAIL — $unanswered unanswered probe(s) and $probedAfter later probe(s), none of " +
                    "which was ever answered: this connection never regained a working path"
    }

    /** Nothing was attempted after the unanswered probe, so recovery was never put to the question. */
    data class NeverRetried(
        val unanswered: Int,
    ) : PoolRecoveryVerdict {
        override val line: String
            get() =
                "INCONCLUSIVE — $unanswered unanswered probe(s) but no migration was attempted " +
                    "afterwards, so pool recovery was never put to the question"
    }
}

/**
 * One connection's probe history. Every "after" count starts at the first unanswered probe, because
 * that is the only point from which pool recovery is a meaningful question.
 */
data class PoolProbeHistory(
    val attempts: Int = 0,
    val unanswered: Int = 0,
    val probedAfterUnanswered: Int = 0,
    val answeredAfterUnanswered: Int = 0,
    val succeededAfterUnanswered: Int = 0,
    val noSpareAfterUnanswered: Int = 0,
) {
    /**
     * The decision, in one place. Order matters: exhaustion is the specific failure #447 names, so it
     * outranks the general one, and an answered probe outranks both — a pool that refused a CID once
     * and then handed one out has, by observation, come back.
     */
    fun verdict(): PoolRecoveryVerdict =
        when {
            unanswered == 0 -> PoolRecoveryVerdict.NeverLostAProbe(attempts)
            answeredAfterUnanswered > 0 ->
                PoolRecoveryVerdict.Recovered(unanswered, answeredAfterUnanswered, succeededAfterUnanswered)
            noSpareAfterUnanswered > 0 -> PoolRecoveryVerdict.Exhausted(unanswered, noSpareAfterUnanswered)
            probedAfterUnanswered > 0 -> PoolRecoveryVerdict.NeverRecovered(unanswered, probedAfterUnanswered)
            else -> PoolRecoveryVerdict.NeverRetried(unanswered)
        }
}

/**
 * The same decision, phrased for the end-of-run roll-up rather than one connection.
 *
 * The roll-up carries one extra trap the per-connection line does not: a **reconnect negotiates a
 * brand-new pool**, so a later connection's clean migrations are not evidence about the connection
 * that lost a probe. Summing the "after" counters keeps that honest, because each of them only ever
 * advanced inside the connection whose probe was lost.
 */
fun PoolRecoveryVerdict.runLine(connections: Int): String =
    when (this) {
        is PoolRecoveryVerdict.NeverLostAProbe ->
            "INCONCLUSIVE — not one probe went unanswered across $connections connection(s); " +
                "this run validates #445 only"
        is PoolRecoveryVerdict.Recovered ->
            "PASS — $unanswered unanswered probe(s), and a connection that lost one went on to have " +
                "$answeredAfter later probe(s) ANSWERED ($succeededAfter completing a migration): " +
                "the pool came back in the field"
        is PoolRecoveryVerdict.Exhausted ->
            "REGRESSION — NoSpareConnectionId answered $noSpareAfter time(s) after an unanswered " +
                "probe (#447 alive in the field)"
        is PoolRecoveryVerdict.NeverRecovered ->
            "FAIL — $unanswered unanswered probe(s) and $probedAfter later probe(s) across " +
                "$connections connection(s), none of them ever answered: no connection that lost a " +
                "path got one back (a later connection's successes prove nothing: fresh pool)"
        is PoolRecoveryVerdict.NeverRetried ->
            "INCONCLUSIVE — $unanswered unanswered probe(s), but no connection that lost one ever " +
                "attempted another migration, so pool recovery was never put to the question"
    }
