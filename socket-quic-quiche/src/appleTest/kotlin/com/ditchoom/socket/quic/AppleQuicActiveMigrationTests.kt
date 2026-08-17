package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [QuicActiveMigrationTestSuite].
 *
 * ## Why this member has to exist at all
 * Active migration used to be tested only by platform-private files (JVM and Linux), so nothing
 * required an Apple counterpart. When Apple shipped with no path factory the gap was invisible: there
 * was no red test, only an absent one. This class is the standing requirement that Apple answers the
 * same questions the other platforms do.
 *
 * ## What "NW owns path moves" actually turned out to mean (measured 2026-08-16, macOS)
 * The historical justification for not wiring migration here was that "explicit quiche path migration
 * via a second local socket does not map to NWConnection (NW owns path moves)". A UDP
 * `nw_connection_t` was held against a live peer while Wi-Fi was switched off and back on, with the
 * effective local endpoint polled throughout:
 *
 * ```
 * t= 4s  ready    path=satisfied/wifi     local=100.110.209.112:51038   5 sent / 5 received
 * t= 5s  -- Wi-Fi off --
 * t= 6s  ready    path=unsatisfied/none   local=100.110.209.112:51038   (unchanged)
 * t= 7s  failed   POSIX 57 (ENOTCONN)     local=100.110.209.112:51038   (unchanged)
 * t=20s  -- Wi-Fi on --
 * t=39s  failed   path=unsatisfied/none   local=100.110.209.112:51038   never recovered
 * ```
 *
 * NW does **not** re-home a UDP connection. It fails it in ~2s, the local endpoint never moves, and
 * `failed` is terminal — 19 further seconds on healthy Wi-Fi did not revive it. So NW does not own path
 * moves for UDP in any sense that helps here; a network change destroys the datapath underneath quiche,
 * which is strictly worse than migration merely being unavailable. The app must open the new path
 * itself — which is Apple's own documented model (betterPathAvailable → new connection → move), and is
 * what `UdpSocketChannelFactory` does here: a fresh `NWConnection` with its own NW-assigned local
 * endpoint, which is exactly the new 4-tuple quiche probes and migrates onto.
 *
 * ## Why the suite's assertions are the right ones for this platform
 * They run entirely on loopback, where no path ever changes — so they do not depend on the measurement
 * above and will not flake with the network conditions of whatever host runs them. Note that Apple is
 * `LocalEndpointSupport.PlatformAssigned`: it cannot bind a caller-named endpoint, and the suite only
 * ever asks for [MigrationTarget.FreshLocalEndpoint], which is served everywhere. That is also why the
 * suite's `Succeeded.localEndpoint` assertion matters most here — on a platform that assigns the
 * endpoint itself, the reported value is the only way a caller can learn where the connection landed.
 */
class AppleQuicActiveMigrationTests : QuicActiveMigrationTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
