@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import platform.posix.F_OK
import platform.posix.access

// Shared test fixtures for the Apple (Network.framework) runs of the cross-platform QUIC suites
// (issue #112). The Apple server's TLS identity comes from a PKCS#12 bundle (see
// QuicTlsConfig.pkcs12Path) generated from the committed PEM by the build's `generateTestP12` task,
// rather than the PEM paths the JVM/Linux servers use.

/** Resolve a file under `testcerts/`, relative to either the module dir or the repo root. */
internal fun appleTestCertPath(name: String): String =
    listOf("testcerts/$name", "socket-quic/testcerts/$name")
        .firstOrNull { access(it, F_OK) == 0 }
        ?: error("Test cert not found: $name (cwd-relative testcerts/ — did generateTestP12 run?)")

/** The TLS config every Apple QUIC server test uses: PEM (for parity) + the PKCS#12 identity. */
internal fun appleQuicTestTlsConfig() =
    QuicTlsConfig(
        certChainPath = appleTestCertPath("cert.crt"),
        privKeyPath = appleTestCertPath("cert.key"),
        pkcs12Path = appleTestCertPath("cert.p12"),
        pkcs12Password = APPLE_TEST_P12_PASSWORD,
    )

/** Must match the build's `generateTestP12` task passphrase. */
internal const val APPLE_TEST_P12_PASSWORD = "testpass"
