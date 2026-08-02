package com.ditchoom.socket

import kotlin.time.Duration

/**
 * *How* a [NetworkMonitor] learns about network changes — sealed, never a string, so a consumer can
 * branch on reactivity exhaustively instead of re-deriving it from `os.name` + JDK version + host OS
 * and silently drifting from what this library actually chose.
 *
 * This exists because "does a change get pushed to me, or do I find out up to N seconds late?" is a
 * *configuration-time* question with real consequences. `../webrtc`'s ICE restart policy is the driving
 * case: `IceRestartPolicy.OnNetworkChange` is only meaningful against a monitor that pushes, and a
 * 5-second detection latency inside a 30-second consent lifetime is a materially different product than
 * an immediate callback. QUIC auto-migration has the same question with a lower stake.
 *
 * The distinction is deliberately about the *signal*, not the quality of the identity — a monitor can be
 * [PlatformSignalled] and still report [com.ditchoom.socket.transport.NetworkId.Unidentified] (browser
 * `online`/`offline` without the Network Information API), and [Polled] monitors on desktop JVM resolve
 * a real [com.ditchoom.socket.transport.NetworkId.Link]. Ask [NetworkState.networkId] (via
 * [NetworkMonitor.state]) about identity and this about latency.
 */
sealed interface MonitorMechanism {
    /**
     * The OS pushes changes as they happen: Android `ConnectivityManager.NetworkCallback`, Apple
     * `NWPathMonitor`, Linux netlink, JDK 21 FFM routing sockets (`AF_NETLINK` / `PF_ROUTE`), browser
     * `online`/`offline` + Network Information API. Sub-second, no polling thread.
     */
    data object PlatformSignalled : MonitorMechanism

    /**
     * State is re-read every [interval]; a change is observed up to [interval] late. The portable
     * fallback where no event-driven API exists — desktop JVM below JDK 21, JVM on Windows, Node.js,
     * and Android with the App Startup initializer stripped.
     */
    data class Polled(
        val interval: Duration,
    ) : MonitorMechanism

    /**
     * The monitor never changes state — [NetworkMonitor.AlwaysAvailable], which reports the network as
     * permanently up. Anything waiting on a transition from this will wait forever, so a consumer whose
     * feature *needs* a transition should treat this as "unsupported here", not as "network is fine".
     */
    data object Static : MonitorMechanism

    /**
     * A monitor that does not declare its mechanism — a third-party implementation predating this
     * property, or a test double. Not an error: it means "this library cannot tell you", the same
     * explicit-unknown stance [com.ditchoom.socket.transport.NetworkId.Unidentified] takes for identity.
     * Treat it as no better than [Polled] of unknown interval.
     */
    data object Unknown : MonitorMechanism
}
