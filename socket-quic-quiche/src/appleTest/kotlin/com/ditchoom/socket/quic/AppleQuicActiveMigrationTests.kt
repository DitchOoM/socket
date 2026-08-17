package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [QuicActiveMigrationTestSuite] — **currently red, on purpose.**
 *
 * Both suite tests fail here today, and they fail for one cause:
 * `WithQuicConnection.apple.kt` passes `udpChannelFactory = null`, so `QuicheDriver.migrationEnabled`
 * (`clientMode && udpChannelFactory != null && ...`) is false and `handleMigrate` answers
 * [MigrationResult.Unsupported] without ever attempting anything.
 *
 * ## The comment that justified the null is stale
 * It reads "explicit quiche path migration via a second local socket does not map to NWConnection (NW
 * owns path moves)". That described the pre-Phase-6 datapath. Apple's client has since been cut over
 * to `UdpSocket.connect` → `DatagramChannelUdpChannel` — the *same* shared adapter Linux and JVM use —
 * so the seam the comment says does not fit is already the seam in use.
 *
 * ## What "NW owns path moves" actually turned out to mean (measured 2026-08-16, macOS)
 * A UDP `nw_connection_t` was held against a live peer while Wi-Fi was switched off and back on, with
 * the effective local endpoint polled throughout:
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
 * `failed` is terminal — 19 further seconds on healthy Wi-Fi did not revive it. So NW does not own
 * path moves for UDP in any sense that helps here; a network change destroys the datapath underneath
 * quiche, which is strictly worse than migration merely being unavailable.
 *
 * The probe built its connection exactly as `NwUdp.def:39-49` does (`nw_endpoint_create_host` +
 * `nw_parameters_create_secure_udp(NW_PARAMETERS_DISABLE_PROTOCOL, NW_PARAMETERS_DEFAULT_CONFIGURATION)`),
 * so the observation transfers to this datapath directly.
 *
 * ## Why these tests are still the right red tests
 * They run entirely on loopback, where no path ever changes — so they do not depend on the measurement
 * above, and they will not flake with the network conditions of whatever host runs them. They pin the
 * narrow, deterministic fact that a client connection on a platform *with* a working quiche engine
 * cannot migrate. Fixing that (an Apple `UdpChannelFactory` opening a second `NWConnection`, per
 * Apple's own better-path model) is what turns them green; surviving a real handoff is a further step
 * that needs the network-change coverage tracked separately.
 *
 * Delete this KDoc's "currently red" framing when the factory lands.
 */
class AppleQuicActiveMigrationTests : QuicActiveMigrationTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
