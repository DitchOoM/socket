package com.ditchoom.socket.http3

import com.ditchoom.socket.HostResolver
import com.ditchoom.socket.Resolution
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.ServerCertVerdict
import com.ditchoom.socket.quic.ServerCertVerifier
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
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

    /**
     * How far the connect got inside the one `handshake` phase, read from what the library already reports
     * (its trace capture and the server-certificate verifier hook), never from the attempt's own guess.
     * Replaced, never mutated. See [ConnectProgress].
     */
    @Volatile
    private var progress: ConnectProgress = ConnectProgress.NotAtEngine

    /**
     * [base] with the library's own observation switched on: a trace capture whose events say when a candidate
     * reached the engine, what datagrams crossed and when QUIC reported the connection established, and a
     * verifier that is consulted only once the platform's trust check has passed. Together they split the
     * `handshake` phase into the waits it is made of.
     */
    fun observing(base: QuicOptions): QuicOptions =
        base.copy(
            trace = QuicTraceCapture(TraceSink { event -> observe(event) }),
            serverCertVerifiers =
                base.serverCertVerifiers +
                    ServerCertVerifier {
                        advance(ConnectProgress.ChainAccepted)
                        ServerCertVerdict.Trusted
                    },
        )

    private fun observe(event: TraceEvent) {
        val now = progress
        when (event) {
            is TraceEvent.DgramOut ->
                when (now) {
                    ConnectProgress.NotAtEngine -> advance(ConnectProgress.Handshaking(1, 0))
                    is ConnectProgress.Handshaking -> advance(now.copy(out = now.out + 1))
                    else -> Unit
                }
            is TraceEvent.DgramIn ->
                when (now) {
                    ConnectProgress.NotAtEngine -> advance(ConnectProgress.Handshaking(0, 1))
                    is ConnectProgress.Handshaking -> advance(now.copy(inbound = now.inbound + 1))
                    else -> Unit
                }
            is TraceEvent.State ->
                if (event.name.endsWith("Established") && (now is ConnectProgress.Handshaking || now == ConnectProgress.NotAtEngine)) {
                    advance(ConnectProgress.EstablishedAwaitingChain)
                }
            else -> Unit
        }
    }

    private fun advance(next: ConnectProgress) {
        if (progress != next) {
            progress = next
            if (next !is ConnectProgress.Handshaking) log = log + "${next.describe()}@${started.elapsedNow().inWholeMilliseconds}ms"
        }
    }

    /** Where the attempt is, naming the wait inside `handshake` when that is the phase. */
    internal fun whereItIs(): String = if (phase == "handshake") "'handshake' (${progress.describe()})" else "'$phase'"

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

    /**
     * The waits inside the `handshake` phase, in the order a connect passes through them. The first fact
     * that proves one has been reached is the only evidence used: a trace sink that has been handed no
     * datagram leaves [NotAtEngine], which says nothing was sent that the library recorded.
     */
    sealed interface ConnectProgress {
        fun describe(): String

        /** No datagram has been recorded: still resolving the name again, racing candidates, or opening the first socket. */
        data object NotAtEngine : ConnectProgress {
            override fun describe() = "no datagram recorded: candidate resolution, address race or socket open"
        }

        /** QUIC datagrams are crossing; [out] sent, [inbound] received, and QUIC has not reported the connection established. */
        data class Handshaking(
            val out: Int,
            val inbound: Int,
        ) : ConnectProgress {
            override fun describe() =
                if (inbound == 0) {
                    "QUIC handshake, $out datagram(s) sent and none received: the server never answered"
                } else {
                    "QUIC handshake, $out datagram(s) sent and $inbound received: the server answered but the handshake did not finish"
                }
        }

        /** QUIC reported Established; the platform trust check has not returned and no verifier has been consulted. */
        data object EstablishedAwaitingChain : ConnectProgress {
            override fun describe() = "QUIC established, waiting on the platform trust evaluation of the server chain"
        }

        /** The trust check passed and the verifier was consulted; what remains is the HTTP/3 or WebTransport bootstrap. */
        data object ChainAccepted : ConnectProgress {
            override fun describe() = "server chain accepted, in the HTTP/3 bootstrap (control streams, SETTINGS)"
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
                        "$endpoint failed in phase ${attempt.whereItIs()} after ${attempt.started.elapsedNow()}: " +
                            "${failure::class.simpleName}: ${failure.message}; phases: $timeline",
                    )
                }
            }
            val stalledIn = attempt.whereItIs()
            work.cancel()
            val unwound = withTimeoutOrNull(cancelGrace) { work.join() } != null
            val timeline = attempt.timeline()
            return if (unwound) {
                Outcome.Unreachable("$endpoint stalled in phase $stalledIn past its $budget budget; phases: $timeline")
            } else {
                Outcome.IgnoredCancellation(
                    "$endpoint stalled in phase $stalledIn past its $budget budget, and was still in phase " +
                        "${attempt.whereItIs()} $cancelGrace after it was cancelled. A wait that ignores cancellation " +
                        "is a defect in this library (or a blocking platform call it makes), not the endpoint's. " +
                        "Phases: $timeline",
                )
            }
        }
    }
}
