@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.QuicTlsConfig
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv

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
 * [wrapTestBody] skips there (mirrored locally since :socket-http3 doesn't depend on :socket-testsuite).
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

    /** Skip on `--standalone` Apple simulators (no `testcerts/` cwd — see [shouldSkipHttp3HarnessOnSimulator]). */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        if (shouldSkipHttp3HarnessOnSimulator()) return
        block()
    }
}

// macOS K/N is OsFamily.MACOSX (real network stack + repo testcerts/ cwd — always runs). iOS/tvOS/watchOS
// simulators run via `simctl spawn --standalone`, whose cwd lacks testcerts/, so skip there unless the
// Gradle build booted the simulator (QUIC_SIM_BOOTED=1). Mirrors :socket-testsuite's
// shouldSkipQuicHarnessOnSimulator so the gate is identical across modules (this module has no testsuite dep).
private fun shouldSkipHttp3HarnessOnSimulator(): Boolean {
    if (kotlin.native.Platform.osFamily == kotlin.native.OsFamily.MACOSX) return false
    return getenv("QUIC_SIM_BOOTED")?.toKString() != "1"
}
