package com.ditchoom.socket.transport

import com.ditchoom.socket.ConnectionFailure
import com.ditchoom.socket.ConnectionFailureReason
import com.ditchoom.socket.SSLProtocolException
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.SocketUnknownHostException

/**
 * The scope at which a [CapabilityCache] remembers that a transport did not work.
 *
 * - [None] — do not remember (the failure said nothing durable about capability).
 * - [PerHost] — "*this server* does not speak this transport" (deterministic server signal:
 *   RST / refused / protocol-or-ALPN mismatch / HTTP 404·426 on the endpoint). Keyed on host.
 * - [PerNetwork] — "*this network path* blocks this transport family" (e.g. the whole UDP/QUIC
 *   family timing out on a UDP-blocked Wi-Fi). Keyed on `networkId` and invalidated on network
 *   change. Emitted by [FallbackTransport]'s family-level contrast (every UDP rung timed out AND a
 *   TCP rung then connected, in one connect), **not** by the per-attempt [FallbackPolicy] — a single
 *   timeout is never enough to poison a network (see [DefaultFallbackPolicy]).
 */
enum class CacheScope { None, PerHost, PerNetwork }

/**
 * The verdict for a single failed connect attempt (RFC_TRANSPORT_FALLBACK §4). Fallback is a 2×2,
 * not one boolean: *should we try the next rung?* is independent of *should we remember this rung as
 * unsupported?*
 *
 * @property fallback try the next transport in the chain. `false` = fatal: stop and rethrow, so a
 *   real problem (bad certificate) is surfaced rather than masked by a weaker rung.
 * @property cacheUnsupported record this transport as unsupported at [scope] so future connects skip
 *   straight past it (a TTL'd hint, never a hard exclusion).
 * @property scope where the demotion is remembered; meaningful only when [cacheUnsupported].
 */
data class FallbackVerdict(
    val fallback: Boolean,
    val cacheUnsupported: Boolean,
    val scope: CacheScope,
) {
    companion object {
        /** Do not fall back — rethrow. Never cached. (TLS certificate / auth failure.) */
        val Fatal = FallbackVerdict(fallback = false, cacheUnsupported = false, scope = CacheScope.None)

        /** Fall forward, but the failure was transient/ambiguous — never poison the cache. */
        val Transient = FallbackVerdict(fallback = true, cacheUnsupported = false, scope = CacheScope.None)

        /** Fall forward AND remember this rung as unsupported at [scope] (deterministic capability error). */
        fun capability(scope: CacheScope) = FallbackVerdict(fallback = true, cacheUnsupported = true, scope = scope)
    }
}

/** Classifies a connect failure into a [FallbackVerdict]. Pure and side-effect-free — the whole of the
 *  fallback correctness lives here, so it is exhaustively unit-tested against the error taxonomy. */
fun interface FallbackPolicy {
    fun classify(error: Throwable): FallbackVerdict
}

/**
 * The default 2×2 built on socket's unified error vocabulary (RFC_UNIFIED §6). The guiding rule:
 * **only deterministic capability errors ever poison the cache.** Transient/ambiguous failures
 * (timeout, unreachable, DNS, an unexplained handshake) fall *forward* so the user still connects,
 * but are never recorded as "unsupported" — so "skipped a transport that was only transiently down"
 * cannot arise by construction (RFC §4).
 *
 * | failure | fall back? | cache unsupported? |
 * |---|---|---|
 * | refused · RST · protocol/ALPN mismatch | yes | yes (per-host) |
 * | timeout · unreachable · no-route · DNS  | yes | no |
 * | TLS **bad certificate**                 | **no** (fatal) | no |
 * | anything else (generic close, catch-all)| yes | no |
 */
object DefaultFallbackPolicy : FallbackPolicy {
    override fun classify(error: Throwable): FallbackVerdict {
        val reason = (error as? ConnectionFailure)?.reason
        return when {
            // Fatal: a definite bad certificate recurs on every TLS rung and must never be masked by
            // silently falling to another transport. (Every rung is encrypted — §8 — so this is about
            // surfacing a real cert problem, not preventing a downgrade.)
            reason == ConnectionFailureReason.TlsBadCertificate -> FallbackVerdict.Fatal

            // Deterministic per-host capability signals → fall forward AND remember (per-host).
            error is SocketConnectionException.Refused -> FallbackVerdict.capability(CacheScope.PerHost)
            error is SocketClosedException.ConnectionReset -> FallbackVerdict.capability(CacheScope.PerHost)
            error is SSLProtocolException ||
                reason == ConnectionFailureReason.TlsProtocolMismatch ->
                FallbackVerdict.capability(CacheScope.PerHost)

            // Transient / ambiguous → fall forward, never poison the cache.
            error is SocketTimeoutException -> FallbackVerdict.Transient
            error is SocketConnectionException.NetworkUnreachable -> FallbackVerdict.Transient
            error is SocketConnectionException.HostUnreachable -> FallbackVerdict.Transient
            error is SocketUnknownHostException -> FallbackVerdict.Transient
            // A generic handshake failure could be an ALPN/version mismatch (capability) OR a cert-ish
            // problem — indistinguishable here. Fall forward so an ALPN mismatch still reaches TCP/WS,
            // but do not poison the cache on the ambiguity.
            reason == ConnectionFailureReason.TlsHandshake -> FallbackVerdict.Transient

            // Anything else (generic close, broken pipe, catch-all I/O, non-socket throwable): try the
            // next rung without poisoning. Cancellation is handled by the caller, never reaches here.
            else -> FallbackVerdict.Transient
        }
    }
}
