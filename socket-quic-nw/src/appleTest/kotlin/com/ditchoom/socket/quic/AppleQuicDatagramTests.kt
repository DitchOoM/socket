package com.ditchoom.socket.quic

/**
 * Apple (Network.framework) run of the shared [QuicDatagramTestSuite] — validates RFC 9221
 * datagrams over a real in-process Apple client↔server connection, including the server-side
 * datagram-flow extraction added for the NWListener server (issue #112).
 */
class AppleQuicDatagramTests : QuicDatagramTestSuite() {
    override fun testTlsConfig() = appleQuicTestTlsConfig()

    /** Skip on `--standalone` Apple simulators (see [shouldSkipQuicHarnessOnSimulator]). */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        if (shouldSkipQuicHarnessOnSimulator()) return
        block()
    }
}
