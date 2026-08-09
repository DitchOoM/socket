package com.ditchoom.socket

/**
 * How good the current link is, as far as the platform will say — the value side of the
 * [NetworkMonitor.linkQuality] surface, gated by [MonitorCapability.linkQuality].
 *
 * This is a **trend input, not a rung**: [NetworkState] answers *whether* traffic can flow, and this
 * answers *how comfortably* — a signal-strength slide is the one warning that precedes a link loss
 * rather than reporting it. It is deliberately a separate flow from [NetworkMonitor.state]:
 * reachability and identity must be sampled atomically (RFC_NETWORK_REACHABILITY §1.2) because
 * consumers make discrete decisions on them; quality is a continuous measurement no decision needs
 * atomically beside the rung, and folding it into [NetworkState] would make every reading a state
 * "change", destroying the equality de-dupe consumers of the rung rely on.
 *
 * [Unavailable] is the honest default, and it is load-bearing: a platform that cannot measure
 * (no public API, wired link, permission withheld at runtime) reports *that*, never a fabricated
 * value and never a stale one. Whether [Rssi] can ever appear at all is answerable before
 * subscribing, via [MonitorCapability.linkQuality] — the same read-once idiom as
 * [MonitorCapability.resolution].
 */
sealed interface LinkQuality {
    /**
     * No measurement is available right now: the monitor never measures
     * ([LinkQualityResolution.None]), the current link has no meaningful strength (wired), the radio
     * is not associated, or the platform declined to report (e.g. a runtime permission it gates the
     * value behind). Not an error, and not zero — the absence of a number is the truthful value.
     */
    data object Unavailable : LinkQuality

    /**
     * Received signal strength of the current link, in [dbm] (dB-milliwatts, negative in practice;
     * closer to zero is stronger — typical Wi-Fi sits between −30 and −90).
     *
     * The value is the platform's own figure, passed through unedited. On links where the platform's
     * "signal strength" is a bearer-specific scale rather than a literal dBm (some cellular reports),
     * the number is still monotone in quality — trend consumers are unaffected, absolute-threshold
     * consumers should calibrate per bearer.
     */
    data class Rssi(
        val dbm: Int,
    ) : LinkQuality
}

/**
 * Whether a [NetworkMonitor] can ever report link quality — the third read-once axis of
 * [MonitorCapability], alongside [MonitorMechanism] and [ReachResolution].
 *
 * Exists so absence is *declared*, not discovered: a consumer that wants signal-strength trend data
 * checks this once at configuration time and picks another strategy if the answer is [None], instead
 * of watching a [LinkQuality.Unavailable] that will never move. Platforms whose public API exposes no
 * per-link signal strength declare [None] — reporting a fake or derived number would be worse than
 * reporting nothing.
 */
sealed interface LinkQualityResolution {
    /**
     * This monitor never reports link quality: its [NetworkMonitor.linkQuality] is constantly
     * [LinkQuality.Unavailable]. The only honest declaration where the platform has no public
     * signal-strength API, and the default for monitors predating this axis.
     */
    data object None : LinkQualityResolution

    /**
     * This monitor reports [LinkQuality.Rssi] whenever the platform exposes a strength for the
     * current link — and still [LinkQuality.Unavailable] whenever it does not (wired link, radio not
     * associated, permission withheld). Declaring [Rssi] is a statement about the *monitor's* reach,
     * not a promise about every emission.
     */
    data object Rssi : LinkQualityResolution
}
