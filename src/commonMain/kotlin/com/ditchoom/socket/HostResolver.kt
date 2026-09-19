package com.ditchoom.socket

/** The IP family of a [ResolvedAddress]. */
enum class IpFamily {
    V4,
    V6,
}

/** One address a name resolved to: an IP literal, never a name. */
data class ResolvedAddress(
    val ip: String,
    val family: IpFamily,
)

/** What a [HostResolver] answered for a name. */
sealed interface Resolution {
    /** The addresses, in the order to try. Never empty: an empty answer is [NoAddress]. */
    data class Resolved(
        val candidates: List<ResolvedAddress>,
    ) : Resolution {
        init {
            require(candidates.isNotEmpty()) { "a resolved answer carries at least one address" }
        }
    }

    /** The resolver answered, and the name has no address. */
    data class NoAddress(
        val host: String,
    ) : Resolution

    /** The resolver could not answer. */
    data class Failed(
        val host: String,
        val cause: Throwable,
    ) : Resolution
}

/** Turns a name into every address it stands for. */
fun interface HostResolver {
    suspend fun resolve(host: String): Resolution

    companion object {
        /** This platform's own resolver: every record it returns, in [happyEyeballsOrder]. */
        fun platform(): HostResolver = platformHostResolver()
    }
}

internal expect fun platformHostResolver(): HostResolver

/**
 * RFC 8305 §4. The resolver's order within a family is kept (the OS already applied RFC 6724), and
 * the families are interleaved starting with the family of the first address, so a family whose
 * every address is unreachable costs one attempt rather than all of them.
 */
fun happyEyeballsOrder(candidates: List<ResolvedAddress>): List<ResolvedAddress> {
    if (candidates.size < 2) return candidates
    val first = candidates.first().family
    val preferred = candidates.filter { it.family == first }
    val other = candidates.filter { it.family != first }
    val ordered = ArrayList<ResolvedAddress>(candidates.size)
    for (i in 0 until maxOf(preferred.size, other.size)) {
        if (i < preferred.size) ordered += preferred[i]
        if (i < other.size) ordered += other[i]
    }
    return ordered
}

/** A platform resolver's raw records as an answer: interleaved, or [Resolution.NoAddress] when there are none. */
internal fun resolvedInOrder(
    records: List<ResolvedAddress>,
    host: String,
): Resolution = if (records.isEmpty()) Resolution.NoAddress(host) else Resolution.Resolved(happyEyeballsOrder(records))

/** How a connect turns a name into the addresses it tries. */
sealed interface NameResolution {
    /**
     * The platform's own way. On Apple, Network.framework resolves the name and races the families
     * itself; everywhere else the connect asks [HostResolver.platform] and makes one attempt per
     * address, in order.
     */
    data object Platform : NameResolution

    /**
     * The given resolver's candidates, one attempt per address in the order given, on every
     * platform. On Apple a TLS connect through a literal address is refused, because
     * Network.framework verifies the certificate against the endpoint it was given.
     */
    data class Via(
        val resolver: HostResolver,
    ) : NameResolution
}

/** The addresses a connect to [host] tries, in order; a name without one is [SocketUnknownHostException]. */
internal suspend fun NameResolution.candidatesFor(host: String): List<ResolvedAddress> {
    val resolver =
        when (this) {
            NameResolution.Platform -> HostResolver.platform()
            is NameResolution.Via -> resolver
        }
    return when (val answer = resolver.resolve(host)) {
        is Resolution.Resolved -> answer.candidates
        is Resolution.NoAddress -> throw SocketUnknownHostException(host, "the name has no address")
        is Resolution.Failed -> throw SocketUnknownHostException(host, cause = answer.cause)
    }
}
