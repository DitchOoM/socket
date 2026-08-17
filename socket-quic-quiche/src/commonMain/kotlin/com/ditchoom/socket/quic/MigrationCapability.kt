package com.ditchoom.socket.quic

/**
 * Whether a [QuicheDriver] can perform RFC 9000 §9 **active connection migration**, and — when it can —
 * everything required to do it.
 *
 * ## Why this is a type and not four constructor defaults
 * This replaces `udpChannelFactory: UdpChannelFactory? = null` plus
 * `peerAddr/peerLen/primaryLocalAddr/primaryLocalLen: Long/Int = 0L/0`, from which the driver derived
 *
 * ```kotlin
 * migrationEnabled = clientMode && udpChannelFactory != null && peerAddr != 0L && primaryLocalAddr != 0L
 * ```
 *
 * Three separate anti-patterns met in that one line: nullability standing in for a capability, `0L`
 * sentinels standing in for "no address", and a four-term boolean standing in for a decision. All of
 * them defaulted to "silently disabled", so a platform that simply *omitted* the arguments got a
 * perfectly working connection that quietly could not migrate — and nothing asked. That is exactly how
 * Apple shipped without migration: not a wrong answer, an unasked question.
 *
 * The parameter is deliberately **required** at every construction site. A new platform, or a new
 * transport backend, cannot compile until it states which case applies. The churn that costs in test
 * doubles is the price of the guarantee, and it is paid once.
 *
 * ## The invariant the type carries
 * In [Supported] the sockaddrs are real by construction — [PinnedSockAddr] rejects the null pointer and
 * a non-positive length — so no code downstream re-checks them, and `0L` can no longer mean "absent".
 * In [Unsupported] there are no addresses at all, rather than addresses that happen to be zero.
 */
@InternalQuicApi
sealed interface MigrationCapability {
    /**
     * This connection does not migrate: a server-accepted driver (RFC 9000 §9 is a client-only
     * capability — only clients migrate in QUIC v1), or a test double that never exercises a path move.
     *
     * `QuicScope.migrate()` answers [MigrationResult.Unsupported] here, which is the honest report: no
     * attempt is made because none can be.
     */
    data object Unsupported : MigrationCapability

    /**
     * A client connection that can open additional local paths.
     *
     * [peer] and [primaryLocal] are the pinned sockaddrs the connection setup already built for
     * `quiche_connect`/`recv_info`; their lifetime is owned by that setup's `onCleanup`, not by this
     * value. [channelFactory] opens each new path's socket.
     */
    data class Supported(
        val peer: PinnedSockAddr,
        val primaryLocal: PinnedSockAddr,
        val channelFactory: UdpChannelFactory,
    ) : MigrationCapability
}

/**
 * A pinned native `sockaddr`: its address and length, as quiche's FFI wants them.
 *
 * Exists to make the `0L`-means-absent sentinel unrepresentable. Construction rejects the null pointer
 * and a non-positive length, so any [PinnedSockAddr] that exists points at a real sockaddr and callers
 * do not re-validate. The memory is **not** owned here — the connection setup that encoded it keeps it
 * alive for the driver's life and frees it in `onCleanup`.
 */
@InternalQuicApi
data class PinnedSockAddr(
    val address: Long,
    val length: Int,
) {
    init {
        require(address != 0L) { "PinnedSockAddr.address is the null pointer — a sockaddr that does not exist cannot be migrated to" }
        require(length > 0) { "PinnedSockAddr.length must be positive, was $length" }
    }
}
