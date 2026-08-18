package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [QuicCapabilityConformanceTestSuite] — **the platform this suite exists
 * for.**
 *
 * Apple is the only target that declares [LocalEndpointSupport.PlatformAssigned]
 * (`WithQuicConnection.apple.kt`), because `UdpSocket.connect` hands the endpoint to `NWConnection` and
 * NW owns endpoint assignment — its own comment calls `localHost`/`localPort` "advisory". The driver
 * acts on that declaration by **refusing** every caller-named endpoint, so if the declaration were
 * wrong in either direction the cost is real and silent: over-claiming binds a socket somewhere other
 * than where `Succeeded` says it landed, under-claiming turns away requests the platform could serve.
 * Nothing measured it until this suite; here it is measured against a real `NWConnection`.
 *
 * It is also the platform whose [MigrationCapability] was `BackendCannotMigrate` in all but name for a
 * year — it shipped with no path factory at all, and no test was red, because none existed to ask.
 */
class AppleQuicCapabilityConformanceTests : QuicCapabilityConformanceTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
