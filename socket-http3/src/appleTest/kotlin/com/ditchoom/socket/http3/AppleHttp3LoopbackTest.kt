// Platform.osFamily, used only to name the platform in the no-engine skip's detail.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.ServerCertificateConstraintSupport
import com.ditchoom.socket.quic.serverCertificateConstraintSupport
import com.ditchoom.socket.testkit.fixtures.TestCerts
import com.ditchoom.socket.testkit.fixtures.locateTestCerts
import com.ditchoom.socket.testkit.skip.SkipGate
import com.ditchoom.socket.testkit.skip.SkipReason
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
     * Skip only for a cause that is actually present — a **measurement**, not an inference.
     *
     * This used to read `osFamily != MACOSX`, i.e. "a simulator, therefore no fixtures". One
     * predicate was standing in for two unrelated facts, and #359 proved it by fixing only one of
     * them: with the fixtures exported, tvOS and watchOS stopped skipping and immediately failed 31
     * tests each with `UnsupportedOperationException`, because **those platforms have no quiche
     * target at all** (Rust Tier-3 — #376). The old inference had been hiding that behind a reason
     * that named the wrong cause; it could not tell the two apart, so it could not report them.
     *
     * Both causes are now asked about separately, in the order a reader would:
     *
     *  1. **Is there an engine?** [serverCertificateConstraintSupport] is the platform's own sealed
     *     answer, and [ServerCertificateConstraintSupport.NoQuicEngine] is documented to mean
     *     exactly "no QUIC engine on this platform — `connect()`/`bind()` throw from
     *     `UnsupportedQuicEngine` before any certificate exists". Reused rather than adding a
     *     parallel capability query, so one value decides this and the two cannot disagree.
     *  2. **Are the fixtures there?** Only meaningful once there is an engine to use them.
     *
     * The gates differ, and that is the point. (1) is [SkipGate.HostCannotProvideIt]: no lane
     * setting can put quiche on watchOS, so `SOCKET_REQUIRE_ALL_TESTS` must not turn it red — the
     * skip stays *visible* in the inventory without being *fatal*. (2) keeps the default gate,
     * because a lane that promises a filesystem and then has no cert is a broken lane.
     */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        if (serverCertificateConstraintSupport is ServerCertificateConstraintSupport.NoQuicEngine) {
            return recordSkip(
                AppleHttp3LoopbackTest::class,
                SkipReason.TransportUnavailable(
                    "${kotlin.native.Platform.osFamily} has no quiche target (Rust Tier-3 — #376), so the " +
                        "default engine is UnsupportedQuicEngine and every connect()/bind() throws before a " +
                        "certificate is ever read",
                ),
                SkipGate.HostCannotProvideIt(capability = "a QUIC engine"),
            )
        }
        when (certs) {
            is TestCerts.Unavailable -> recordSkip(AppleHttp3LoopbackTest::class, certs.asSkipReason())
            is TestCerts.Available -> block()
        }
    }
}
