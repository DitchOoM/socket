package com.ditchoom.socket.http3

import com.ditchoom.socket.HostResolver
import com.ditchoom.socket.Resolution
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One attempt at a public endpoint, split into named phases, so that a failure says **where** it
 * stopped and **whose** fault it can be.
 *
 * The public-endpoint interop tests talk to servers on the internet. When one of them used to hit its
 * whole-test timeout, nothing said whether name resolution, the handshake, the request or the response
 * had stalled, or whether the endpoint was merely unreachable. Every attempt now runs through [run],
 * which keeps a timestamped log of the phases the test enters ([enter]) and ends in one [Outcome]:
 *
 * - [Outcome.Done]: the exchange finished; the caller asserts on its value.
 * - [Outcome.Unreachable]: a phase failed or ran out of budget. Name resolution failing, a handshake
 *   that never completes, a server that stops answering: the internet, not this library. A skip,
 *   logged with the phase.
 * - [Outcome.IgnoredCancellation]: the budget ran out and the attempt was cancelled, but it did not
 *   finish within [CANCEL_GRACE]. Every wait in this library must honour cancellation, whatever the
 *   endpoint does, so this is a defect in the library (or a blocking platform call it makes), and the
 *   test fails, naming the phase that was stuck.
 *
 * The attempt runs detached from the test's own scope, so a stuck one cannot hold the test past its
 * verdict; it is abandoned once reported.
 */
internal class PublicEndpointAttempt private constructor(
    private val endpoint: String,
) {
    private val started = TimeSource.Monotonic.markNow()

    /** Replaced, never mutated, so the watchdog can read it while a stuck attempt still runs. */
    @Volatile
    private var log: List<String> = emptyList()

    /** The phase the attempt is in; read by the watchdog when the budget runs out. */
    @Volatile
    var phase: String = "not started"
        private set

    /** Mark that the attempt has reached [name]. */
    fun enter(name: String) {
        phase = name
        log = log + "$name@${started.elapsedNow().inWholeMilliseconds}ms"
    }

    /**
     * Resolve [host] through the resolver every connect uses, as its own phase, so a name that does not
     * resolve (or resolves slowly) is told apart from a handshake that does not complete.
     */
    suspend fun resolve(host: String) {
        enter("dns")
        when (val answer = HostResolver.platform().resolve(host)) {
            is Resolution.Resolved -> enter("dns answered ${answer.candidates.size} address(es)")
            is Resolution.NoAddress -> throw UnreachableEndpoint("$host has no address")
            is Resolution.Failed -> throw UnreachableEndpoint("$host did not resolve: ${answer.cause}")
        }
    }

    private fun timeline(): String = log.joinToString(" -> ").ifEmpty { "(no phase entered)" }

    sealed interface Outcome<out T> {
        val report: String

        class Done<T>(
            val value: T,
            override val report: String,
        ) : Outcome<T>

        class Unreachable(
            override val report: String,
        ) : Outcome<Nothing>

        class IgnoredCancellation(
            override val report: String,
        ) : Outcome<Nothing>
    }

    /** The endpoint could not be reached at all. */
    class UnreachableEndpoint(
        message: String,
    ) : Exception(message)

    companion object {
        /**
         * How long a cancelled attempt may take to finish. Generous: unwinding sends a CONNECTION_CLOSE
         * and frees native state, which takes milliseconds; anything near this long is a wait that does
         * not honour cancellation.
         */
        val CANCEL_GRACE: Duration = 10.seconds

        suspend fun <T> run(
            endpoint: String,
            budget: Duration,
            cancelGrace: Duration = CANCEL_GRACE,
            block: suspend PublicEndpointAttempt.() -> T,
        ): Outcome<T> {
            val attempt = PublicEndpointAttempt(endpoint)
            val scope = CoroutineScope(Dispatchers.Default + Job() + CoroutineName("public-endpoint $endpoint"))
            val work = scope.async { attempt.block() }
            val finished = withTimeoutOrNull(budget) { work.join() } != null
            if (finished) {
                val failure = work.getCompletionExceptionOrNull()
                val timeline = attempt.timeline()
                return if (failure == null) {
                    Outcome.Done(work.await(), "$endpoint finished: $timeline")
                } else {
                    Outcome.Unreachable(
                        "$endpoint failed in phase '${attempt.phase}' after ${attempt.started.elapsedNow()}: " +
                            "${failure::class.simpleName}: ${failure.message}; phases: $timeline",
                    )
                }
            }
            val stalledIn = attempt.phase
            work.cancel()
            val unwound = withTimeoutOrNull(cancelGrace) { work.join() } != null
            val timeline = attempt.timeline()
            return if (unwound) {
                Outcome.Unreachable("$endpoint stalled in phase '$stalledIn' past its $budget budget; phases: $timeline")
            } else {
                Outcome.IgnoredCancellation(
                    "$endpoint stalled in phase '$stalledIn' past its $budget budget, and was still in phase " +
                        "'${attempt.phase}' $cancelGrace after it was cancelled. A wait that ignores cancellation " +
                        "is a defect in this library (or a blocking platform call it makes), not the endpoint's. " +
                        "Phases: $timeline",
                )
            }
        }
    }
}
