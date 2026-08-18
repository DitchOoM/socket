package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [PeerTransportParamsLayoutTestSuite] — DitchOoM/socket#388.
 *
 * This is the backend where the defect was first *measured*: a live loopback connection reported the
 * peer as forbidding active migration, which made [QuicScope.migrate] answer
 * [MigrationResult.Unmoved.Impossible.PeerForbids] and the automatic reactor cancel itself — on a
 * platform where re-homing is the whole reason migration exists, since a network change kills an
 * `NWConnection`'s datapath outright rather than moving it. Forcing `active_conn_id_limit = 7` and
 * watching offset 80 come back as 7 is what localised it to the struct layout rather than to the Apple
 * server's config path, which advertises `disable_active_migration = false` correctly.
 */
class ApplePeerTransportParamsLayoutTests : PeerTransportParamsLayoutTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
