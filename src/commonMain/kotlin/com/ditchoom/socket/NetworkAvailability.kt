package com.ditchoom.socket

/**
 * Represents the availability of the network as reported by a [NetworkMonitor].
 */
enum class NetworkAvailability {
    /** At least one usable network path exists. */
    AVAILABLE,

    /** No usable network path exists. */
    UNAVAILABLE,

    /** Network state has not yet been determined (e.g., monitor just started). */
    UNKNOWN,
}
