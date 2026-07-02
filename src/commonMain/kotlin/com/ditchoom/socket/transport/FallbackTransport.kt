package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.TransportsExhausted
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

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
 * MVP scope (§11): sequential, with a per-attempt connect timeout from [TransportConfig.connectTimeout].
 * Staggered family racing (§5) is a later addition around this same loop.
 */
class FallbackTransport(
    private val chain: List<Transport>,
    private val policy: FallbackPolicy = DefaultFallbackPolicy,
    private val cache: CapabilityCache = NoOpCapabilityCache,
) : Transport {
    init {
        require(chain.isNotEmpty()) { "FallbackTransport requires a non-empty transport chain" }
    }

    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream {
        val order = cache.order(hostname, config.networkId, chain)
        val failures = ArrayList<TransportFailure>(order.size)
        for (transport in order) {
            try {
                val stream = attempt(transport, hostname, port, config)
                cache.recordSuccess(hostname, config.networkId, transport)
                return stream
            } catch (timeout: TimeoutCancellationException) {
                // Per-attempt bound exceeded → transient: fall forward, never poison the cache.
                failures += TransportFailure(transport, timeout, FallbackVerdict.Transient)
            } catch (cancel: CancellationException) {
                throw cancel // genuine external cancellation — must propagate, never swallow
            } catch (error: Throwable) {
                val verdict = policy.classify(error)
                if (verdict.cacheUnsupported) {
                    cache.recordUnsupported(verdict.scope, hostname, config.networkId, transport)
                }
                if (!verdict.fallback) throw error // fatal (bad cert): stop, don't mask
                failures += TransportFailure(transport, error, verdict)
            }
        }
        throw TransportsExhausted(hostname, port, failures)
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
}
