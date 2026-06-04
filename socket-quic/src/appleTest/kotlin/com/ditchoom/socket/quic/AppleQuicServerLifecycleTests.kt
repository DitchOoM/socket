package com.ditchoom.socket.quic

/** Apple (Network.framework) run of the shared [QuicServerLifecycleTestSuite] — common-API parity, issue #112. */
class AppleQuicServerLifecycleTests : QuicServerLifecycleTestSuite() {
    override fun testTlsConfig() = appleQuicTestTlsConfig()

    /** Skip on `--standalone` Apple simulators (see [shouldSkipQuicHarnessOnSimulator]). */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        if (shouldSkipQuicHarnessOnSimulator()) return
        block()
    }
}
