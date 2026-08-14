@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import platform.posix.F_OK
import platform.posix.access

/**
 * Apple subclass of [Http3LoopbackTestSuite] — a comprehensive HTTP/3 exercise on the Apple **quiche**
 * backend (plain GET/POST, dynamic QPACK, server push, the full WebTransport stream matrix,
 * close/drain/reset, and middleware), over the same Cloudflare-quiche QUIC engine JVM/Android/Linux use,
 * provided transitively through socket-quic-default → :socket-quic-quiche. (This replaced the
 * Network.framework backend in the quiche-on-Apple pivot, which deleted the macos-26 libquic teardown UAF
 * and the in-process NW-loopback flake this suite used to hit.)
 *
 * Like Linux's [LinuxHttp3LoopbackTest] it probes the cert/key on the filesystem and configures the
 * quiche server with loose PEM cert+key (no PKCS#12 — that was an NW-identity requirement). macOS K/N
 * runs the full suite; iOS/tvOS/watchOS `--standalone` simulators lack the `testcerts/` cwd, so
 * [wrapTestBody] reports a typed skip there rather than returning green.
 *
 * WebTransport datagrams work on the quiche backend (RFC 9221), so the inherited
 * [Http3LoopbackTestSuite.webTransport_datagramRoundTrip] runs unmodified.
 */
class AppleHttp3LoopbackTest : Http3LoopbackTestSuite() {
    private fun certPath(name: String): String {
        val candidates =
            listOf(
                "testcerts/$name",
                "socket-http3/testcerts/$name",
            )
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() =
        QuicTlsConfig(
            certChainPath = certPath("cert.crt"),
            privKeyPath = certPath("cert.key"),
        )

    /** Skip on `--standalone` Apple simulators (no `testcerts/` cwd — see [simulatorLacksFixtures]). */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        val skip = simulatorLacksFixtures()
        if (skip != null) return recordSkip(AppleHttp3LoopbackTest::class, skip)
        block()
    }
}

// macOS K/N is OsFamily.MACOSX (real network stack + repo testcerts/ cwd — always runs). iOS/tvOS/watchOS
// simulators run via `simctl spawn --standalone`, whose cwd lacks testcerts/, so skip there.
//
// Returns the typed reason rather than a Boolean, and the caller routes it through `recordSkip`, so
// the skip lands in the test XML as a greppable line. It previously early-returned, which the report
// records as a PASS — 31 tests in this suite reported green on three simulator lanes having executed
// nothing. Mirrors :socket-testsuite's shouldSkipQuicHarnessOnSimulator; both now share
// :socket-testkit's SkipReason, which is why the gate can no longer drift between the two modules.
private fun simulatorLacksFixtures(): SkipReason? {
    if (kotlin.native.Platform.osFamily == kotlin.native.OsFamily.MACOSX) return null
    return SkipReason.SimulatorLacksFixtures(
        "${kotlin.native.Platform.osFamily} simulator runs under `simctl spawn --standalone`, " +
            "whose cwd has no testcerts/ — the loopback server has no cert+key to bind with",
    )
}
