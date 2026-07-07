package com.ditchoom.socket.transport

/**
 * A seam a transport or protocol implements to actively check whether an established connection
 * is still alive — faster than transport keepalive or the OS TCP timeout alone.
 *
 * [ReconnectingConnection] drives [probe] on a network-path change (a
 * [NetworkMonitor.networkId][com.ditchoom.socket.NetworkMonitor.networkId] change). A path change
 * — airplane-mode toggle, Wi‑Fi↔cellular handoff, a transport swap — is the strongest signal that
 * a previously-live connection may now be half-open. If the probe returns [Result.Dead], the
 * current connection is torn down and reconnection begins immediately, instead of lingering until
 * keepalive or the OS notices.
 *
 * Implementations should send whatever cheap round-trip their protocol already offers — MQTT
 * `PINGREQ`, WebSocket ping/pong, QUIC `PING` — and await the ack within a short deadline:
 *
 * ```kotlin
 * val liveness = Liveness {
 *     if (withTimeoutOrNull(2.seconds) { pingRoundTrip() } != null) {
 *         Liveness.Result.Alive
 *     } else {
 *         Liveness.Result.Dead
 *     }
 * }
 * ```
 *
 * The seam is **opt-in and default-inert**: with no [Liveness] installed, or on a monitor that
 * never reports path changes (the default [NetworkMonitor][com.ditchoom.socket.NetworkMonitor]
 * whose `networkId` stays `Unidentified`, or [NetworkMonitor.AlwaysAvailable][com.ditchoom.socket.NetworkMonitor.Companion]),
 * [probe] is never called and behavior is unchanged.
 *
 * See `RFC_TRANSPORT_FALLBACK.md` §7 (liveness noted as a dependency of reconnection).
 */
fun interface Liveness {
    /**
     * Check whether the connection is currently alive.
     *
     * Called on a network-path change. Should perform a bounded round-trip and never suspend
     * indefinitely — an implementation that cannot decide within its deadline must return
     * [Result.Unknown] (treated as alive, so no connection is torn down on an inconclusive probe).
     */
    suspend fun probe(): Result

    enum class Result {
        /** The connection answered the probe — keep it. */
        Alive,

        /** The connection is confirmed dead — tear it down and reconnect immediately. */
        Dead,

        /** Liveness could not be determined — treated as [Alive]; no teardown. */
        Unknown,
    }
}
