package com.ditchoom.socket.quic

/**
 * Apple (Network.framework) run of the shared [QuicServerTestSuite] — the first in-process
 * client↔server QUIC round-trip on Apple K/N (issue #112). Before the NWListener server existed,
 * the shared server suite couldn't run on Apple at all.
 */
class AppleQuicServerTests : QuicServerTestSuite() {
    override fun testTlsConfig() = appleQuicTestTlsConfig()

    override fun localhostTlsConfig() = appleQuicLocalhostTlsConfig()

    override fun localhostCertPem() = appleReadFileText(appleTestCertPath("localhost.crt"))

    override fun unrelatedCaPem() = appleReadFileText(appleTestCertPath("cert.crt"))

    /**
     * Skip on Apple simulators launched in `--standalone` mode, where Network.framework's QUIC
     * datapath is unreachable (see [shouldSkipQuicHarnessOnSimulator]). macOS K/N runs the full
     * suite; the iOS simulator runs it only under the booted mode CI enables.
     */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        if (shouldSkipQuicHarnessOnSimulator()) return
        block()
    }
}
