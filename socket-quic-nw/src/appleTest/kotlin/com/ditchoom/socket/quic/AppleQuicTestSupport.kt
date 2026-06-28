@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.getenv

// Shared test fixtures for the Apple (Network.framework) runs of the cross-platform QUIC suites
// (issue #112). The Apple server's TLS identity comes from a PKCS#12 bundle (see
// QuicTlsConfig.pkcs12Path) generated from the committed PEM by the build's `generateTestP12` task,
// rather than the PEM paths the JVM/Linux servers use.

/**
 * Resolve a file under `testcerts/`. On macOS K/N the cwd is the module dir, so the cwd-relative paths
 * below work. On a BOOTED iOS simulator (standalone=false) the test process is `simctl spawn`'d with a
 * cwd that is NOT the module dir, so cwd-relative `testcerts/` resolves nothing — which made every
 * cert-dependent Apple suite fail at setup once the simulator harness was un-skipped. CI passes the
 * module's ABSOLUTE testcerts/ dir via `SIMCTL_CHILD_QUIC_TESTCERTS_DIR` (simctl forwards it to the test
 * process as `QUIC_TESTCERTS_DIR`); the sim-spawned binary runs on the host and can read that host path,
 * so try it first when present and fall back to the cwd-relative lookup everywhere else.
 */
internal fun appleTestCertPath(name: String): String {
    getenv("QUIC_TESTCERTS_DIR")?.toKString()?.takeIf { it.isNotEmpty() }?.let { dir ->
        val abs = "$dir/$name"
        if (access(abs, F_OK) == 0) return abs
    }
    return listOf("testcerts/$name", "socket-quic/testcerts/$name")
        .firstOrNull { access(it, F_OK) == 0 }
        ?: error("Test cert not found: $name (cwd-relative testcerts/ — did generateTestP12 run?)")
}

/** The TLS config every Apple QUIC server test uses: PEM (for parity) + the PKCS#12 identity. */
internal fun appleQuicTestTlsConfig() =
    QuicTlsConfig(
        certChainPath = appleTestCertPath("cert.crt"),
        privKeyPath = appleTestCertPath("cert.key"),
        pkcs12Path = appleTestCertPath("cert.p12"),
        pkcs12Password = APPLE_TEST_P12_PASSWORD,
    )

/**
 * A TLS identity from a named W3C `serverCertificateHashes` constraint fixture (`pinned`,
 * `pinned-expired`, `pinned-toolong`, `pinned-rsa`) — the build's `generateTestP12` packages each into
 * a `.p12` the Network.framework listener presents. The compliant `pinned` drives the accept test; the
 * violators drive the per-constraint reject tests.
 */
internal fun appleQuicPinnedTlsConfig(name: String = "pinned") =
    QuicTlsConfig(
        certChainPath = appleTestCertPath("$name.crt"),
        privKeyPath = appleTestCertPath("$name.key"),
        pkcs12Path = appleTestCertPath("$name.p12"),
        pkcs12Password = APPLE_TEST_P12_PASSWORD,
    )

/**
 * The self-signed `localhost` identity (SAN DNS:localhost,IP:127.0.0.1) the CA-pinning tests need
 * — distinct from [appleQuicTestTlsConfig] so the chain both anchors to a pinnable cert AND matches
 * the "localhost" connect hostname. Backed by `localhost.p12`, which `generateTestP12` produces
 * alongside `cert.p12`.
 */
internal fun appleQuicLocalhostTlsConfig() =
    QuicTlsConfig(
        certChainPath = appleTestCertPath("localhost.crt"),
        privKeyPath = appleTestCertPath("localhost.key"),
        pkcs12Path = appleTestCertPath("localhost.p12"),
        pkcs12Password = APPLE_TEST_P12_PASSWORD,
    )

/** Read a (text) cert file into a String via posix — test-only, no buffer-lib dependency needed. */
internal fun appleReadFileText(path: String): String =
    memScoped {
        val fp = fopen(path, "r") ?: error("Cannot open $path")
        try {
            val sb = StringBuilder()
            val bufSize = 4096
            val buf = allocArray<ByteVar>(bufSize)
            while (true) {
                val n = fread(buf, 1.convert(), (bufSize - 1).convert(), fp).toInt()
                if (n <= 0) break
                buf[n] = 0
                sb.append(buf.toKString())
            }
            sb.toString()
        } finally {
            fclose(fp)
        }
    }

/** Must match the build's `generateTestP12` task passphrase. */
internal const val APPLE_TEST_P12_PASSWORD = "testpass"
