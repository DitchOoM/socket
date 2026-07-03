package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.TransportsExhausted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** One rung's failure, retained for the aggregate [TransportsExhausted] diagnostic. */
data class TransportFailure(
    val transport: Transport,
    val error: Throwable,
    val verdict: FallbackVerdict,
)

/**
 * Composition primitive (RFC_TRANSPORT_FALLBACK §3): tries a chain of `Transport`s in order until one
 * connects, and *is itself a [Transport]* — so a protocol library binds to it exactly like any single
 * transport and never learns which rung won. Everything reduces to `Transport`, so this is small and
 * protocol-agnostic; QUIC/WebTransport enter the chain via their `SessionOwningByteStream` projection
 * (RFC_UNIFIED §3.3), each pre-addressed on its instance (path/ALPN, RFC_UNIFIED §4).
 *
 * On each failure it consults [policy] for the 2×2 verdict — *fall forward vs. fatal*, and *whether to
 * remember the rung as unsupported* — and feeds deterministic capability failures to [cache] so later
 * connects skip them. The two guarantees that make fallback safe:
 * - **Fatal is fatal** — a bad-certificate failure ([FallbackVerdict.fallback] = false) stops the
 *   chain and rethrows, so a real security problem is surfaced, never masked by a weaker rung (§8).
 * - **Cancellation is sacred** — genuine coroutine cancellation propagates untouched; only a
 *   *per-attempt* [withTimeout] bound is caught and treated as a transient fall-forward.
 *
 * **Staggered family racing (§5, opt-in via [stagger]).** QUIC and WebTransport share fate — both
 * ride UDP — so on a UDP-blocked network pure-sequential order eats *two* full timeouts before
 * reaching TCP. With a [stagger], the chain splits into its two [TransportFamily] lanes: the family
 * of the first cache-ordered rung starts immediately, the other lane starts after the stagger *or*
 * as soon as the first lane exhausts, whichever comes first (RFC 8305 Happy Eyeballs, staggered).
 * Each lane runs sequentially within itself. The first success wins and the loser is cancelled — a
 * connection that completes after losing is closed, never leaked. A fatal verdict on either lane
 * still collapses the whole race. [RECOMMENDED_STAGGER] is a sensible head start; `null` (the
 * default) keeps the ordered sequential behavior.
 *
 * **Per-network learning (§6).** When the *whole* UDP family timed out while a TCP rung then
 * connected — the family-level contrast that a single ambiguous timeout can never provide — the UDP
 * rungs are recorded as unsupported at [CacheScope.PerNetwork], keyed on [TransportConfig.networkId].
 * The next connect on the same network starts on the TCP lane; a network change (a different
 * `NetworkId`) or the cache TTL re-probes UDP automatically. [networkId] supplies the identity at
 * connect time (typically `{ monitor.networkId.value }` from a platform `NetworkMonitor`); a config
 * that already carries an explicit identity wins over the producer.
 */
class FallbackTransport(
    private val chain: List<Transport>,
    private val policy: FallbackPolicy = DefaultFallbackPolicy,
    private val cache: CapabilityCache = InMemoryCapabilityCache(),
    private val networkId: () -> NetworkId = { NetworkId.Unidentified },
    private val stagger: Duration? = null,
) : Transport {
    init {
        require(chain.isNotEmpty()) { "FallbackTransport requires a non-empty transport chain" }
        require(stagger == null || stagger > Duration.ZERO) { "stagger must be positive (or null for sequential)" }
    }

    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream {
        // Stamp the connect-time network identity unless the caller pinned one explicitly.
        val effectiveConfig =
            if (config.networkId == NetworkId.Unidentified) config.copy(networkId = networkId()) else config
        val order = cache.order(hostname, effectiveConfig.networkId, chain)
        val headFamily = order.first().family
        return if (stagger != null && order.any { it.family != headFamily }) {
            raced(order, headFamily, hostname, port, effectiveConfig, stagger)
        } else {
            sequential(order, hostname, port, effectiveConfig)
        }
    }

    /** The RFC §5 suggested head start for the preferred family when racing is enabled. */
    companion object {
        val RECOMMENDED_STAGGER: Duration = 250.milliseconds
    }

    // ---- one rung ----------------------------------------------------------------------------

    private sealed interface RungResult {
        class Connected(
            val stream: ByteStream,
        ) : RungResult

        class Failed(
            val failure: TransportFailure,
        ) : RungResult
    }

    /**
     * Runs a single rung: connect bounded by the per-attempt timeout, record the cache verdicts.
     * Returns [RungResult.Failed] for fall-forward failures; **throws** on a fatal verdict and on
     * genuine cancellation.
     */
    private suspend fun runRung(
        transport: Transport,
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): RungResult =
        try {
            val stream = attempt(transport, hostname, port, config)
            cache.recordSuccess(hostname, config.networkId, transport)
            RungResult.Connected(stream)
        } catch (timeout: TimeoutCancellationException) {
            // Per-attempt bound exceeded → transient: fall forward, never poison the cache.
            RungResult.Failed(TransportFailure(transport, timeout, FallbackVerdict.Transient))
        } catch (cancel: CancellationException) {
            throw cancel // genuine external cancellation — must propagate, never swallow
        } catch (error: Throwable) {
            val verdict = policy.classify(error)
            if (verdict.cacheUnsupported) {
                cache.recordUnsupported(verdict.scope, hostname, config.networkId, transport)
            }
            if (!verdict.fallback) throw error // fatal (bad cert): stop, don't mask
            RungResult.Failed(TransportFailure(transport, error, verdict))
        }

    private suspend fun attempt(
        transport: Transport,
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream {
        val timeout = config.connectTimeout
        // Bound each attempt so a hung transport can't stall the whole chain. A transport that already
        // honors connectTimeout internally just makes this a harmless outer guard.
        return if (timeout.isFinite() && timeout > Duration.ZERO) {
            withTimeout(timeout) { transport.connect(hostname, port, config) }
        } else {
            transport.connect(hostname, port, config)
        }
    }

    // ---- sequential (default) ----------------------------------------------------------------

    private suspend fun sequential(
        order: List<Transport>,
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream {
        val failures = ArrayList<TransportFailure>(order.size)
        for (transport in order) {
            when (val result = runRung(transport, hostname, port, config)) {
                is RungResult.Connected -> {
                    recordPathBlocksUdp(transport, failures, hostname, config.networkId)
                    return result.stream
                }
                is RungResult.Failed -> failures += result.failure
            }
        }
        throw TransportsExhausted(hostname, port, failures)
    }

    // ---- staggered family race (§5) ------------------------------------------------------------

    /** Per-lane state: [failures] is written once, before [exhausted] completes (happens-before via Job). */
    private class Lane(
        val rungs: List<Transport>,
    ) {
        val exhausted = Job()
        var failures: List<TransportFailure> = emptyList()
    }

    private suspend fun raced(
        order: List<Transport>,
        headFamily: TransportFamily,
        hostname: String,
        port: Int,
        config: TransportConfig,
        stagger: Duration,
    ): ByteStream =
        coroutineScope {
            val head = Lane(order.filter { it.family == headFamily })
            val chase = Lane(order.filter { it.family != headFamily })
            val winner = CompletableDeferred<ByteStream>()

            // A fatal verdict throws out of a lane's launch, which fails this scope: the other lane is
            // cancelled and coroutineScope rethrows the original error — same "fatal is fatal" as sequential.
            launchLane(head, other = chase, winner = winner, hostname, port, config, gate = null)
            launchLane(chase, other = head, winner = winner, hostname, port, config, gate = stagger to head.exhausted)
            launch {
                head.exhausted.join()
                chase.exhausted.join()
                winner.completeExceptionally(TransportsExhausted(hostname, port, head.failures + chase.failures))
            }

            try {
                winner.await()
            } finally {
                // First outcome decided (success, exhaustion, or the scope is already failing) — stop the rest.
                coroutineContext.cancelChildren()
            }
        }

    private fun CoroutineScope.launchLane(
        lane: Lane,
        other: Lane,
        winner: CompletableDeferred<ByteStream>,
        hostname: String,
        port: Int,
        config: TransportConfig,
        gate: Pair<Duration, Job>?,
    ) = launch {
        // The chase lane holds at the gate for the stagger head start — released early if the head
        // lane exhausts first, so a fast-failing head never makes the chase wait the full stagger.
        if (gate != null) withTimeoutOrNull(gate.first) { gate.second.join() }
        val failures = ArrayList<TransportFailure>()
        for (transport in lane.rungs) {
            when (val result = runRung(transport, hostname, port, config)) {
                is RungResult.Connected -> {
                    val otherFailures = if (other.exhausted.isCompleted) other.failures else emptyList()
                    recordPathBlocksUdp(transport, failures + otherFailures, hostname, config.networkId)
                    // Lost the race after connecting → close, never leak a second live connection.
                    // NonCancellable: by now the race is decided and this lane is being cancelled —
                    // the cleanup must still run to completion.
                    if (!winner.complete(result.stream)) {
                        withContext(NonCancellable) { result.stream.close() }
                    }
                    return@launch
                }
                is RungResult.Failed -> failures += result.failure
            }
        }
        lane.failures = failures
        lane.exhausted.complete()
    }

    // ---- per-network family contrast (§6) -------------------------------------------------------

    /**
     * The one signal allowed to write [CacheScope.PerNetwork]: within a single connect, **every** UDP
     * rung in the chain timed out and a TCP rung then connected. A lone timeout stays transient (§4);
     * a refused/RST UDP rung is a per-host signal already recorded by the policy; an unattempted UDP
     * rung (race won before its lane finished) means the family wasn't judged. Only the full contrast
     * — UDP family dead, TCP family alive, same network, same instant — reads as "this path blocks UDP".
     */
    private fun recordPathBlocksUdp(
        winner: Transport,
        failures: List<TransportFailure>,
        hostname: String,
        networkId: NetworkId,
    ) {
        if (winner.family != TransportFamily.Tcp) return
        if (networkId == NetworkId.Unidentified) return // per-network scope is off (RFC §12)
        val udpRungs = chain.filter { it.family == TransportFamily.Udp }
        if (udpRungs.isEmpty()) return
        val timedOut =
            failures
                .filter { it.transport.family == TransportFamily.Udp && isTimeout(it.error) }
                .map { it.transport }
                .toSet()
        if (timedOut != udpRungs.toSet()) return
        for (rung in udpRungs) {
            cache.recordUnsupported(CacheScope.PerNetwork, hostname, networkId, rung)
        }
    }

    private fun isTimeout(error: Throwable): Boolean = error is TimeoutCancellationException || error is SocketTimeoutException
}
