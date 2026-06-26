@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.QuicTlsConfig
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv

/**
 * Apple subclass of [Http3LoopbackTestSuite] — the first comprehensive HTTP/3 exercise on
 * Network.framework (plain GET/POST, dynamic QPACK, server push, the full WebTransport stream matrix,
 * close/drain/reset, and middleware), all over the NW QUIC server+client provided transitively through
 * socket-quic-default → :socket-quic-nw.
 *
 * Like Linux's [LinuxHttp3LoopbackTest] it probes the cert/key on the filesystem. macOS K/N runs the full
 * suite; iOS/tvOS/watchOS `--standalone` simulators can't reach the NW QUIC datapath (and lack the
 * `testcerts/` cwd), so [wrapTestBody] skips there — the same gate the :socket-quic-nw Apple suites use
 * (mirrored locally since :socket-http3 doesn't depend on :socket-testsuite). Unlike Linux's quiche
 * server, NW's QUIC listener can only build its `sec_identity_t` from a
 * PKCS#12 bundle (loose PEM cert+key cannot — see [QuicTlsConfig.pkcs12Path]); the `generateHttp3TestP12`
 * Gradle task exports `testcerts/cert.p12` (passphrase `testpass`) and the Apple K/N test tasks depend on it.
 *
 * **WebTransport datagrams now work on Apple via the OS-26 Swift backend** (issue #173): when datagrams
 * are requested the engine routes to `connectQuicSwift` / `buildAppleQuicSwiftServer`
 * (`NetworkConnection<QUIC>`), where datagrams and inbound streams coexist on one connection — so the
 * inherited [Http3LoopbackTestSuite.webTransport_datagramRoundTrip] is no longer overridden/`@Ignore`d.
 * (Below macOS/iOS 26 the engine falls back to the legacy group backend, where datagrams remain
 * unavailable; this suite runs on macOS 26 here.)
 */
class AppleHttp3LoopbackTest : Http3LoopbackTestSuite() {
    private fun certPath(name: String): String {
        val candidates =
            listOf(
                "testcerts/$name",
                "socket-http3/testcerts/$name",
            )
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates — did generateHttp3TestP12 run?)")
    }

    override fun testTlsConfig() =
        QuicTlsConfig(
            certChainPath = certPath("cert.crt"),
            privKeyPath = certPath("cert.key"),
            // Network.framework's QUIC listener imports its identity from this PKCS#12 blob; the PEM
            // paths above are ignored by the Apple server (kept for cross-platform config symmetry).
            pkcs12Path = certPath("cert.p12"),
            pkcs12Password = "testpass",
        )

    /** Skip on `--standalone` Apple simulators (see [shouldSkipHttp3HarnessOnSimulator]). */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        if (shouldSkipHttp3HarnessOnSimulator()) return
        block()
    }
}

// macOS K/N is OsFamily.MACOSX (real network stack — always runs). iOS/tvOS/watchOS simulators run via
// `simctl spawn --standalone` by default, which can't reach Network.framework QUIC, so skip there unless
// the Gradle build booted the simulator (QUIC_SIM_BOOTED=1). Mirrors :socket-testsuite's
// shouldSkipQuicHarnessOnSimulator so the gate is identical across modules (this module has no testsuite dep).
private fun shouldSkipHttp3HarnessOnSimulator(): Boolean {
    if (kotlin.native.Platform.osFamily == kotlin.native.OsFamily.MACOSX) return false
    return getenv("QUIC_SIM_BOOTED")?.toKString() != "1"
}
