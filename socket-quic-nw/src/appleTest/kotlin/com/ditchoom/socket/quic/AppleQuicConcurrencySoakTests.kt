package com.ditchoom.socket.quic

/** Apple (Network.framework) run of the shared [QuicConcurrencySoakTestSuite] — common-API parity, issue #112. */
class AppleQuicConcurrencySoakTests : QuicConcurrencySoakTestSuite() {
    override fun testTlsConfig() = appleQuicTestTlsConfig()

    /** Skip on `--standalone` Apple simulators (see [shouldSkipQuicHarnessOnSimulator]). */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        if (shouldSkipQuicHarnessOnSimulator()) return
        block()
    }

    /**
     * Network.framework allows only ONE multiplex QUIC group per (host, port) endpoint per process
     * (a 2nd concurrent group to the same endpoint → POSIX ENOMEM; proven deterministically in
     * issue #112, incl. against a public endpoint — it is not a global connection cap). The NW model
     * is to multiplex streams over one connection, covered by
     * [manyConcurrentStreamsOnOneConnectionRoundTrip] (which now handles 1024 streams after the
     * initial_max_streams fix). So the many-connections-to-one-endpoint case does not apply here.
     */
    override fun supportsConcurrentConnectionsToSameEndpoint(): Boolean = false
}
