package com.ditchoom.socket.quic

/**
 * Apple K/Native member of [QuicServerLifecycleTestSuite] — the Apple counterpart of
 * [JvmQuicServerLifecycleTestSuite], [LinuxQuicServerLifecycleTests] and
 * `AndroidQuicServerLifecycleTests` (issue #296).
 *
 * Asserts the black-box lifecycle invariants of the Apple quiche server (dual-stack POSIX UDP bind):
 * close completes promptly, close does not deadlock while a connection handler is blocked, repeated
 * bind/close cycles stay prompt, and the connections flow completes on close. The JVM-only reflection
 * assertion on `serverJob` is not portable to K/N and is deliberately not ported (same call the Linux
 * member made).
 *
 * Cert resolution comes from the committed `cert.crt`/`cert.key` via [AppleTestCerts], which
 * materializes the embedded PEM into `TMPDIR` when the cwd has no `testcerts/` — so this suite runs for
 * real on a `--standalone` iOS simulator too, with no [wrapTestBody] skip. K/N compiles quiche via
 * cinterop, so there is no `UnsatisfiedLinkError` skip path either.
 */
class AppleQuicServerLifecycleTests : QuicServerLifecycleTestSuite() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig
}
