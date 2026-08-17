package com.ditchoom.socket.quic

/**
 * Marks a declaration as part of the QUIC **driver seam** — public only so the platform source sets and
 * the test doubles in this module can reach it, never as a supported consumer API.
 *
 * These types (`UdpChannel`, `UdpChannelFactory`, `SendOutcome`, `NewPath`, `QuicheDriver`,
 * `MigrationCapability`) sit between quiche and the platform datapaths. They change whenever the driver's
 * internals change, with no deprecation cycle and no semver protection.
 *
 * Their visibility was an omission rather than a decision: a `git grep` across the consumer applications
 * (1786 tracked Kotlin files) found **zero** references to any of them. This annotation records that
 * finding as a contract, so the freedom it buys is explicit rather than assumed the next time one of
 * them needs to change.
 *
 * Opting in means accepting that a patch release may break your code.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message =
        "This is the internal QUIC driver seam, not a supported API: it changes without deprecation " +
            "and without a semver guarantee. Opt in with @OptIn(InternalQuicApi::class) only if you " +
            "accept that a patch release may break you.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class InternalQuicApi
