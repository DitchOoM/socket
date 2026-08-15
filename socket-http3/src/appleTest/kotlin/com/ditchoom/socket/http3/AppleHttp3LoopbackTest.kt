package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.testkit.fixtures.TestCerts
import com.ditchoom.socket.testkit.fixtures.locateTestCerts
import com.ditchoom.socket.testkit.skip.recordSkip

/**
 * Apple subclass of [Http3LoopbackTestSuite] — a comprehensive HTTP/3 exercise on the Apple **quiche**
 * backend (plain GET/POST, dynamic QPACK, server push, the full WebTransport stream matrix,
 * close/drain/reset, and middleware), over the same Cloudflare-quiche QUIC engine JVM/Android/Linux use,
 * provided transitively through socket-quic-default → :socket-quic-quiche. (This replaced the
 * Network.framework backend in the quiche-on-Apple pivot, which deleted the macos-26 libquic teardown UAF
 * and the in-process NW-loopback flake this suite used to hit.)
 *
 * Like Linux's [LinuxHttp3LoopbackTest] it probes the cert/key on the filesystem and configures the
 * quiche server with loose PEM cert+key (no PKCS#12 — that was an NW-identity requirement).
 *
 * The simulator lanes run this suite too, as of #359. They used to skip all 31 invocations because
 * `simctl spawn --standalone` starts in the device's data container rather than the module directory,
 * so the cwd-relative cert probe found nothing; the build now exports the module's `testcerts/` by
 * absolute path and [locateTestCerts] prefers it. [wrapTestBody] still reports a typed skip if the
 * pair genuinely cannot be found, which on a macOS lane means a broken checkout and goes red.
 *
 * WebTransport datagrams work on the quiche backend (RFC 9221), so the inherited
 * [Http3LoopbackTestSuite.webTransport_datagramRoundTrip] runs unmodified.
 */
class AppleHttp3LoopbackTest : Http3LoopbackTestSuite() {
    private val certs = locateTestCerts(moduleDir = "socket-http3")

    override fun testTlsConfig(): QuicTlsConfig {
        val available =
            certs as? TestCerts.Available
                ?: error("testTlsConfig() reached with no fixtures; wrapTestBody should have skipped first")
        return QuicTlsConfig(
            certChainPath = available.certChainPath,
            privKeyPath = available.privKeyPath,
        )
    }

    /**
     * Skip only when the fixtures are genuinely absent — a **measurement**, not an inference.
     *
     * This used to read `osFamily != MACOSX`, i.e. "a simulator, therefore no fixtures". That was
     * true when written and stopped being true the moment the build began exporting an absolute
     * path into the sandbox (#359): the suite would have gone on skipping 31 invocations per lane
     * against fixtures sitting right there, and nothing would have said so. Probing the thing the
     * skip is *about* means the skip disappears by itself the day the cause does.
     */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        when (certs) {
            is TestCerts.Unavailable -> recordSkip(AppleHttp3LoopbackTest::class, certs.asSkipReason())
            is TestCerts.Available -> block()
        }
    }
}
