package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [QuicDatagramTestSuite] — the Apple counterpart of [JvmQuicDatagramTests],
 * [LinuxQuicDatagramTests] and `AndroidQuicDatagramTests` (issue #289). Before this, `appleTest` held no
 * member of any shared `Quic*TestSuite` at all, so the RFC 9221 datagram round-trip — and the Apple
 * client's `DriverDatagramAdapter` under it — had never been executed on macOS or iOS.
 *
 * The suite bodies are entirely backend-neutral, so there is nothing Apple-specific to assert beyond the
 * wiring: cert resolution comes from the shared [AppleTestCerts], which materializes the embedded PEM
 * into `TMPDIR` when the cwd has no `testcerts/`. That is deliberate — it means this suite runs **for
 * real** on the `--standalone` iOS simulator rather than self-skipping the way the :socket-http3 and
 * :socket-webtransport Apple suites must (they have no equivalent fallback, so the missing cwd is fatal
 * there). No `wrapTestBody` override: there is no condition under which this should silently pass
 * without running.
 */
class AppleQuicDatagramTests : QuicDatagramTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
