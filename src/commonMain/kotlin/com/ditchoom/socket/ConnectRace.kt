package com.ditchoom.socket

import com.ditchoom.socket.transport.DefaultFallbackPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** How a connect paces its attempts across the addresses a name resolved to. */
sealed interface ConnectPacing {
    /** One attempt at a time: the next starts only once the previous one has failed. */
    data object Sequential : ConnectPacing

    /**
     * Every candidate at once. RFC 8305 §5 staggers so a race costs at most one extra connection per
     * delay, which is the right trade when the candidates are one server's addresses. It is the wrong
     * trade when the first packet to each candidate is doing work of its own: a peer-to-peer connect
     * sends to every candidate simultaneously so each NAT sees an outbound packet before the peer's
     * arrives, and a stagger would delay exactly the punches it depends on.
     */
    data object Simultaneous : ConnectPacing

    /**
     * RFC 8305 §5. The next attempt starts [attemptDelay] after the previous one unless that one has
     * already completed, a failure starts the next at once, the first success wins, and every other
     * attempt is cancelled. One that completes after losing is closed, never leaked.
     */
    data class Staggered(
        val attemptDelay: Duration,
    ) : ConnectPacing {
        init {
            require(attemptDelay > Duration.ZERO) { "an attempt delay is positive; Sequential waits for a failure instead" }
        }

        companion object {
            /** The RFC's recommended Connection Attempt Delay. */
            val RECOMMENDED: Staggered = Staggered(250.milliseconds)
        }
    }
}

/** What one attempt's failure means for the others. */
sealed interface AttemptVerdict {
    /** This address did not answer; another may. */
    data object TryOthers : AttemptVerdict

    /** The failure is about the peer, not the path: no other address can do better, and the race ends with it. */
    data object Fatal : AttemptVerdict

    companion object {
        /** [DefaultFallbackPolicy]'s word: a failure it would not fall back from is [Fatal]. */
        fun of(error: Throwable): AttemptVerdict = if (DefaultFallbackPolicy.classify(error).fallback) TryOthers else Fatal
    }
}

/**
 * One connect over [candidates] under [pacing]. [attempt] opens one connection to one candidate and
 * owns its own cleanup on failure or cancellation; [verdict] says whether a failure ends the race;
 * [close] releases a connection that completed after the race was decided, and its own failure
 * never costs the winner. The first success is the answer; the last failure is the error.
 *
 * Every attempt that has not completed when the race is decided is cancelled, and one that completes
 * anyway is handed to [close] — so an attempt is either the answer, closed, or cancelled, and there
 * is no fourth thing for a connection to be.
 *
 * Public because the QUIC handshake races the same way a TCP connect does: it is the same mechanism
 * over a different attempt, and a second copy of it would be a second set of lifetime bugs.
 */
suspend fun <C, T> connectRace(
    candidates: List<C>,
    pacing: ConnectPacing,
    verdict: (Throwable) -> AttemptVerdict,
    close: suspend (T) -> Unit,
    attempt: suspend (C) -> T,
): T {
    require(candidates.isNotEmpty()) { "a connect needs at least one address" }
    return when (pacing) {
        ConnectPacing.Sequential -> firstReachable(candidates, verdict, attempt)
        // Zero delay is the simultaneous case exactly: the pacer's per-attempt wait returns at once,
        // so the loop launches every candidate in one pass without any of them having started.
        ConnectPacing.Simultaneous -> staggered(candidates, Duration.ZERO, verdict, close, attempt)
        is ConnectPacing.Staggered -> staggered(candidates, pacing.attemptDelay, verdict, close, attempt)
    }
}

/**
 * One attempt per candidate, in order. A cancelled scope leaves at once; a single attempt's own
 * deadline does not.
 */
internal suspend fun <C, T> firstReachable(
    candidates: List<C>,
    verdict: (Throwable) -> AttemptVerdict,
    attempt: suspend (C) -> T,
): T {
    val attempts = candidates.iterator()
    while (true) {
        val candidate = attempts.next()
        try {
            return attempt(candidate)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            if (verdict(e) is AttemptVerdict.Fatal || !attempts.hasNext()) throw e
        }
    }
}

private sealed interface Outcome<out T> {
    data class Connected<T>(
        val value: T,
    ) : Outcome<T>

    data class Failed(
        val cause: Throwable,
    ) : Outcome<Nothing>
}

private suspend fun <C, T> staggered(
    candidates: List<C>,
    attemptDelay: Duration,
    verdict: (Throwable) -> AttemptVerdict,
    close: suspend (T) -> Unit,
    attempt: suspend (C) -> T,
): T =
    coroutineScope {
        val winner = CompletableDeferred<T>()
        // One slot per attempt, written once by that attempt: nothing here is shared between attempts.
        val outcomes = List(candidates.size) { CompletableDeferred<Outcome<T>>() }

        // The pacer: the next attempt after the delay, sooner once the previous one has failed, none
        // once one has connected.
        launch {
            for ((index, candidate) in candidates.withIndex()) {
                launch {
                    val outcome =
                        try {
                            Outcome.Connected(attempt(candidate))
                        } catch (e: Throwable) {
                            currentCoroutineContext().ensureActive()
                            Outcome.Failed(e)
                        }
                    when (outcome) {
                        is Outcome.Connected ->
                            // Decided already, by another attempt or by a fatal failure: this one lost.
                            if (!winner.complete(outcome.value)) withContext(NonCancellable) { runCatching { close(outcome.value) } }
                        is Outcome.Failed ->
                            if (verdict(outcome.cause) is AttemptVerdict.Fatal) winner.completeExceptionally(outcome.cause)
                    }
                    outcomes[index].complete(outcome)
                }
                val early = withTimeoutOrNull(attemptDelay) { outcomes[index].await() }
                if (early is Outcome.Connected) break
            }
        }

        // Exhaustion: every attempt failed, and the last failure is the error.
        launch {
            val failures =
                outcomes.map { outcome ->
                    when (val decided = outcome.await()) {
                        is Outcome.Connected -> return@launch
                        is Outcome.Failed -> decided.cause
                    }
                }
            winner.completeExceptionally(failures.last())
        }

        try {
            winner.await()
        } finally {
            coroutineContext.cancelChildren()
        }
    }
