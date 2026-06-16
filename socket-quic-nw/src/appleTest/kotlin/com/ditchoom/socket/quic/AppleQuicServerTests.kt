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

    /**
     * Network.framework surfaces a peer RESET_STREAM distinctly as [ReadResult.Reset]
     * (unlike the quiche driver, which collapses it to EOF). This is the regression guard
     * for issue #81: before the fix, [NWQuicByteStream] didn't implement [com.ditchoom.buffer.flow.Resettable],
     * so [QuicByteStream.reset] degraded to a graceful FIN and the peer saw `End`, not `Reset`.
     */
    override fun assertResetObservedByPeer(resultClassName: String?) {
        kotlin.test.assertEquals(
            "Reset",
            resultClassName,
            "Apple must surface a peer RESET_STREAM as ReadResult.Reset, not a graceful close",
        )
    }
}
